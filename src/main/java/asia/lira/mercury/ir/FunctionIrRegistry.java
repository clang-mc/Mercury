package asia.lira.mercury.ir;

import net.minecraft.server.function.CommandFunction;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FunctionIrRegistry {
    private static final FunctionIrRegistry INSTANCE = new FunctionIrRegistry();

    private final Map<Identifier, ParsedFunctionIr> parsedFunctions = new LinkedHashMap<>();
    private final Map<Identifier, SemanticFunctionIr> semanticFunctions = new LinkedHashMap<>();

    private FunctionIrRegistry() {
    }

    public static FunctionIrRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void registerParsed(ParsedFunctionIr functionIr) {
        parsedFunctions.put(functionIr.id(), functionIr);
        semanticFunctions.remove(functionIr.id());
    }

    public synchronized void rebuildSemantic(Map<Identifier, ? extends CommandFunction<?>> loadedFunctions) {
        Map<Identifier, ParsedFunctionIr> nextParsed = new LinkedHashMap<>();
        for (Map.Entry<Identifier, ? extends CommandFunction<?>> entry : loadedFunctions.entrySet()) {
            ParsedFunctionIr functionIr = parsedFunctions.get(entry.getKey());
            if (functionIr != null) {
                nextParsed.put(entry.getKey(), functionIr);
            }
        }

        parsedFunctions.clear();
        parsedFunctions.putAll(nextParsed);

        semanticFunctions.clear();
        for (ParsedFunctionIr functionIr : parsedFunctions.values()) {
            semanticFunctions.put(functionIr.id(), buildSemantic(functionIr));
        }
    }

    public synchronized Optional<ParsedFunctionIr> getParsed(Identifier id) {
        return Optional.ofNullable(parsedFunctions.get(id));
    }

    public synchronized Optional<SemanticFunctionIr> getSemantic(Identifier id) {
        return Optional.ofNullable(semanticFunctions.get(id));
    }

    public synchronized List<Identifier> getParsedIds() {
        return List.copyOf(parsedFunctions.keySet());
    }

    public synchronized int getParsedCount() {
        return parsedFunctions.size();
    }

    public synchronized int getSemanticCount() {
        return semanticFunctions.size();
    }

    public synchronized List<Identifier> getSemanticIds() {
        return List.copyOf(semanticFunctions.keySet());
    }

    private static SemanticFunctionIr buildSemantic(ParsedFunctionIr functionIr) {
        List<SemanticNode> nodes = new ArrayList<>(functionIr.nodes().size());
        boolean hasTailCallRewrite = false;

        for (int i = 0; i < functionIr.nodes().size(); i++) {
            ParseNode parseNode = functionIr.nodes().get(i);
            boolean tailPosition = i == functionIr.nodes().size() - 1;
            SemanticNode semanticNode;

            if (parseNode instanceof CommandParseNode commandNode) {
                SemanticOpKind opKind = switch (commandNode.controlFlowKind()) {
                    case NONE -> SemanticOpKind.INVOKE;
                    case EXECUTE -> SemanticOpKind.EXECUTE_CHAIN;
                    case FUNCTION -> SemanticOpKind.FUNCTION_CALL;
                    case RETURN -> SemanticOpKind.RETURN;
                    case RETURN_RUN_FUNCTION -> SemanticOpKind.RETURN_RUN_CALL;
                    case EXECUTE_RUN_FUNCTION -> SemanticOpKind.EXECUTE_CHAIN;
                    case OPAQUE -> SemanticOpKind.OPAQUE;
                };

                @Nullable Identifier rewrittenTarget = null;
                if (tailPosition && commandNode.targetFunctionId() != null) {
                    if (commandNode.controlFlowKind() == ControlFlowKind.FUNCTION
                            || commandNode.controlFlowKind() == ControlFlowKind.RETURN_RUN_FUNCTION
                            || commandNode.controlFlowKind() == ControlFlowKind.EXECUTE_RUN_FUNCTION) {
                        opKind = SemanticOpKind.JUMP;
                        rewrittenTarget = commandNode.targetFunctionId();
                        hasTailCallRewrite = true;
                    }
                }

                nodes.add(new SemanticNode(
                        opKind,
                        commandNode.sourceText(),
                        commandNode.targetFunctionId(),
                        rewrittenTarget,
                        commandNode.notes()
                ));
                continue;
            }

            MacroTemplateParseNode macroNode = (MacroTemplateParseNode) parseNode;
            semanticNode = new SemanticNode(
                    SemanticOpKind.MACRO_TEMPLATE,
                    macroNode.sourceText(),
                    null,
                    null,
                    List.of("macro-template")
            );
            nodes.add(semanticNode);
        }

        return new SemanticFunctionIr(functionIr.id(), nodes, hasTailCallRewrite);
    }

    public enum ParseNodeKind {
        COMMAND,
        MACRO_TEMPLATE
    }

    public enum ControlFlowKind {
        NONE,
        EXECUTE,
        FUNCTION,
        RETURN,
        RETURN_RUN_FUNCTION,
        EXECUTE_RUN_FUNCTION,
        OPAQUE
    }

    public enum SemanticOpKind {
        INVOKE,
        EXECUTE_CHAIN,
        FUNCTION_CALL,
        RETURN,
        RETURN_RUN_CALL,
        JUMP,
        MACRO_TEMPLATE,
        OPAQUE
    }

    public sealed interface ParseNode permits CommandParseNode, MacroTemplateParseNode {
        ParseNodeKind kind();

        String sourceText();
    }

    public record ParsedFunctionIr(
            Identifier id,
            boolean hasMacros,
            List<String> variableSlots,
            List<ParseNode> nodes
    ) {
    }

    public record ContextLayerIr(
            int stageIndex,
            List<String> nodePath,
            int rangeStart,
            int rangeEnd,
            boolean forked,
            boolean hasRedirectModifier,
            boolean executable
    ) {
    }

    public record ParsedArgumentIr(
            String name,
            int rangeStart,
            int rangeEnd,
            String parsedSlice,
            String valueType,
            String valuePreview,
            boolean staticallyBound
    ) {
    }

    public record CommandBindingIr(
            List<String> rootPath,
            String commandClassName,
            boolean hasExecutableTarget,
            boolean hasDirectMethodBinding
    ) {
    }

    public record CommandParseNode(
            String sourceText,
            List<ContextLayerIr> contextLayers,
            List<ParsedArgumentIr> arguments,
            CommandBindingIr binding,
            ControlFlowKind controlFlowKind,
            @Nullable Identifier targetFunctionId,
            List<String> notes
    ) implements ParseNode {
        @Override
        public ParseNodeKind kind() {
            return ParseNodeKind.COMMAND;
        }
    }

    public record MacroTemplateParseNode(
            String sourceText,
            List<String> segments,
            List<String> variables,
            List<Integer> dependentVariableSlots
    ) implements ParseNode {
        @Override
        public ParseNodeKind kind() {
            return ParseNodeKind.MACRO_TEMPLATE;
        }
    }

    public record SemanticNode(
            SemanticOpKind opKind,
            String sourceText,
            @Nullable Identifier targetFunctionId,
            @Nullable Identifier rewrittenJumpTarget,
            List<String> notes
    ) {
    }

    public record SemanticFunctionIr(
            Identifier id,
            List<SemanticNode> nodes,
            boolean hasTailCallRewrite
    ) {
    }
}
