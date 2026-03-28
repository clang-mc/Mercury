package asia.lira.mercury.jit.registry;

import asia.lira.mercury.ir.FunctionActionCaptureRegistry;
import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.mixin.accessor.MixinCommandContextAccessor;
import asia.lira.mercury.mixin.accessor.MixinSingleCommandActionAccessor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.ControlFlowAware;
import net.minecraft.command.Frame;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UnknownCommandBindingRegistry {
    private static final UnknownCommandBindingRegistry INSTANCE = new UnknownCommandBindingRegistry();
    private static final Constructor<CommandContext<?>> COMMAND_CONTEXT_CTOR = findCommandContextConstructor();
    private static final MethodHandle BRIGADIER_RUN_HANDLE = findBrigadierRunHandle();

    private final Map<BindingKey, Integer> idsByKey = new LinkedHashMap<>();
    private final Map<Integer, BindingPlan> plansById = new LinkedHashMap<>();

    private UnknownCommandBindingRegistry() {
    }

    public static UnknownCommandBindingRegistry getInstance() {
        return INSTANCE;
    }

    public void clear() {
        idsByKey.clear();
        plansById.clear();
    }

    public void rebuild(Collection<FunctionIrRegistry.ParsedFunctionIr> functions) {
        clear();

        Map<Identifier, List<SourcedCommandAction<?>>> actionCaptures = FunctionActionCaptureRegistry.getInstance().snapshot();
        int nextId = 0;
        for (FunctionIrRegistry.ParsedFunctionIr functionIr : functions) {
            List<SourcedCommandAction<?>> actions = actionCaptures.getOrDefault(functionIr.id(), List.of());
            for (int nodeIndex = 0; nodeIndex < functionIr.nodes().size(); nodeIndex++) {
                FunctionIrRegistry.ParseNode node = functionIr.nodes().get(nodeIndex);
                if (!(node instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                    continue;
                }
                SourcedCommandAction<?> action = nodeIndex < actions.size() ? actions.get(nodeIndex) : null;
                if (action == null) {
                    continue;
                }

                BindingPlan plan = analyze(functionIr.id(), nodeIndex, commandNode, action, nextId);
                idsByKey.put(new BindingKey(functionIr.id(), nodeIndex), nextId);
                plansById.put(nextId, plan);
                nextId++;
            }
        }
    }

    public @Nullable Integer bindingId(Identifier functionId, int nodeIndex) {
        return idsByKey.get(new BindingKey(functionId, nodeIndex));
    }

    public @Nullable BindingPlan plan(int bindingId) {
        return plansById.get(bindingId);
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractServerCommandSource<T>> int invokeBoundCommand(int bindingId, T source) throws Throwable {
        BindingPlan plan = requirePlan(bindingId);
        if (plan.kind() != BindingKind.REFLECTIVE) {
            throw new IllegalStateException("Binding " + bindingId + " is not reflective");
        }
        CommandContext<T> reboundContext = (CommandContext<T>) rebindContext((CommandContext<?>) plan.contextTemplate(), source);
        return (int) plan.methodHandle().invoke(reboundContext);
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractServerCommandSource<T>> void invokeActionFallback(
            int bindingId,
            T source,
            CommandExecutionContext<T> context,
            Frame frame
    ) {
        BindingPlan plan = requirePlan(bindingId);
        if (plan.kind() != BindingKind.ACTION_FALLBACK) {
            throw new IllegalStateException("Binding " + bindingId + " is not an action fallback");
        }
        ((SourcedCommandAction<T>) plan.action()).execute(source, context, frame);
    }

    private BindingPlan analyze(
            Identifier functionId,
            int nodeIndex,
            FunctionIrRegistry.CommandParseNode commandNode,
            SourcedCommandAction<?> action,
            int bindingId
    ) {
        if (commandNode.controlFlowKind() != FunctionIrRegistry.ControlFlowKind.NONE) {
            return BindingPlan.actionFallback(bindingId, functionId, nodeIndex, commandNode.sourceText(), action, "control-flow");
        }
        if (!(action instanceof net.minecraft.command.SingleCommandAction.Sourced<?> sourcedAction)) {
            return BindingPlan.actionFallback(bindingId, functionId, nodeIndex, commandNode.sourceText(), action, "unsupported-action");
        }

        ContextChain<?> contextChain = ((MixinSingleCommandActionAccessor<?>) sourcedAction).mercury$getContextChain();
        List<CommandContext<?>> contexts = flatten(contextChain);
        if (contexts.isEmpty()) {
            return BindingPlan.actionFallback(bindingId, functionId, nodeIndex, commandNode.sourceText(), action, "missing-context");
        }

        CommandContext<?> executableContext = contexts.get(contexts.size() - 1);
        if (!isReflectivelyBindable(commandNode, contexts, executableContext)) {
            return BindingPlan.actionFallback(bindingId, functionId, nodeIndex, commandNode.sourceText(), action, "unsafe-context");
        }

        Command<?> command = executableContext.getCommand();
        MethodHandle boundRun = BRIGADIER_RUN_HANDLE.bindTo(command);
        return BindingPlan.reflective(bindingId, functionId, nodeIndex, commandNode.sourceText(), executableContext, boundRun);
    }

    private static boolean isReflectivelyBindable(
            FunctionIrRegistry.CommandParseNode commandNode,
            List<CommandContext<?>> contexts,
            CommandContext<?> executableContext
    ) {
        if (!commandNode.binding().hasExecutableTarget()) {
            return false;
        }
        for (FunctionIrRegistry.ContextLayerIr layer : commandNode.contextLayers()) {
            if (layer.forked() || layer.hasRedirectModifier()) {
                return false;
            }
        }
        for (CommandContext<?> context : contexts) {
            if (context.isForked() || context.getRedirectModifier() != null) {
                return false;
            }
        }

        Command<?> command = executableContext.getCommand();
        return command != null && !(command instanceof ControlFlowAware<?>);
    }

    private static List<CommandContext<?>> flatten(ContextChain<?> chain) {
        List<CommandContext<?>> contexts = new ArrayList<>();
        ContextChain<?> current = chain;
        int guard = 0;
        while (current != null && guard++ < 32) {
            contexts.add(current.getTopContext());
            current = current.nextStage();
        }
        return contexts;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> CommandContext<T> rebindContext(CommandContext<?> template, T source) {
        if (template == null) {
            return null;
        }

        MixinCommandContextAccessor<?> accessor = (MixinCommandContextAccessor<?>) template;
        CommandContext<T> reboundChild = rebindContext(accessor.mercury$getChild(), source);

        try {
            return (CommandContext<T>) COMMAND_CONTEXT_CTOR.newInstance(
                    source,
                    accessor.mercury$getInput(),
                    (Map<String, ParsedArgument<T, ?>>) (Map<?, ?>) accessor.mercury$getArguments(),
                    (Command<T>) accessor.mercury$getCommand(),
                    (CommandNode<T>) accessor.mercury$getRootNode(),
                    (List<ParsedCommandNode<T>>) (List<?>) accessor.mercury$getNodes(),
                    accessor.mercury$getRange(),
                    reboundChild,
                    (RedirectModifier<T>) accessor.mercury$getModifier(),
                    accessor.mercury$getForks()
            );
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to rebuild command context", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Constructor<CommandContext<?>> findCommandContextConstructor() {
        try {
            Constructor<CommandContext<?>> constructor = (Constructor<CommandContext<?>>) (Constructor<?>) CommandContext.class.getDeclaredConstructor(
                    Object.class,
                    String.class,
                    Map.class,
                    Command.class,
                    CommandNode.class,
                    List.class,
                    StringRange.class,
                    CommandContext.class,
                    RedirectModifier.class,
                    boolean.class
            );
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to locate CommandContext constructor", exception);
        }
    }

    private static MethodHandle findBrigadierRunHandle() {
        try {
            return MethodHandles.publicLookup().findVirtual(
                    Command.class,
                    "run",
                    MethodType.methodType(int.class, CommandContext.class)
            );
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to locate Brigadier Command.run", exception);
        }
    }

    private BindingPlan requirePlan(int bindingId) {
        BindingPlan plan = plansById.get(bindingId);
        if (plan == null) {
            throw new IllegalStateException("Missing unknown-command binding " + bindingId);
        }
        return plan;
    }

    private record BindingKey(Identifier functionId, int nodeIndex) {
    }

    public enum BindingKind {
        REFLECTIVE,
        ACTION_FALLBACK
    }

    public record BindingPlan(
            int id,
            Identifier functionId,
            int nodeIndex,
            String sourceText,
            BindingKind kind,
            @Nullable CommandContext<?> contextTemplate,
            @Nullable MethodHandle methodHandle,
            @Nullable SourcedCommandAction<?> action,
            String note
    ) {
        private static BindingPlan reflective(
                int id,
                Identifier functionId,
                int nodeIndex,
                String sourceText,
                CommandContext<?> contextTemplate,
                MethodHandle methodHandle
        ) {
            return new BindingPlan(id, functionId, nodeIndex, sourceText, BindingKind.REFLECTIVE, contextTemplate, methodHandle, null, "run(CommandContext)");
        }

        private static BindingPlan actionFallback(
                int id,
                Identifier functionId,
                int nodeIndex,
                String sourceText,
                SourcedCommandAction<?> action,
                String note
        ) {
            return new BindingPlan(id, functionId, nodeIndex, sourceText, BindingKind.ACTION_FALLBACK, null, null, action, note);
        }
    }
}
