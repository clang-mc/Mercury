package asia.lira.mercury.jit.registry;

import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.pipeline.CommandLoweringKind;
import asia.lira.mercury.jit.pipeline.CommandLoweringPlan;
import asia.lira.mercury.jit.pipeline.SlotKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JitPreparationRegistry {
    private static final Pattern SCOREBOARD_PLAYER_PATTERN = Pattern.compile(
            "^scoreboard\\s+players\\s+(set|add|remove|get|reset)\\s+(\\S+)\\s+(\\S+)(?:\\s+.*)?$"
    );
    private static final Pattern SCOREBOARD_OPERATION_PATTERN = Pattern.compile(
            "^scoreboard\\s+players\\s+operation\\s+(\\S+)\\s+(\\S+)\\s+(=|\\+=|-=|\\*=|/=|%=|<|>|><)\\s+(\\S+)\\s+(\\S+)$"
    );
    private static final JitPreparationRegistry INSTANCE = new JitPreparationRegistry();

    private final ObjectiveRegistry objectiveRegistry = new ObjectiveRegistry();
    private final OptimizedSlotRegistry slotRegistry = new OptimizedSlotRegistry();
    private final Map<Identifier, PreparedFunctionPlan> preparedFunctions = new LinkedHashMap<>();

    private JitPreparationRegistry() {
    }

    public static JitPreparationRegistry getInstance() {
        return INSTANCE;
    }

    public void rebuild(Collection<FunctionIrRegistry.ParsedFunctionIr> functions) {
        objectiveRegistry.clear();
        slotRegistry.clear();
        preparedFunctions.clear();

        for (FunctionIrRegistry.ParsedFunctionIr functionIr : functions) {
            List<PreparedCommandPlan> commandPlans = new ArrayList<>();
            List<FunctionIrRegistry.ParseNode> nodes = functionIr.nodes();
            for (int i = 0; i < nodes.size(); i++) {
                FunctionIrRegistry.ParseNode node = nodes.get(i);
                if (!(node instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                    commandPlans.add(new PreparedCommandPlan(i, node.sourceText(), CommandLoweringPlan.of(
                            CommandLoweringKind.FALLBACK,
                            List.of(),
                            List.of("macro-template")
                    )));
                    continue;
                }

                commandPlans.add(new PreparedCommandPlan(
                        i,
                        commandNode.sourceText(),
                        analyzeCommand(commandNode)
                ));
            }
            preparedFunctions.put(functionIr.id(), new PreparedFunctionPlan(functionIr.id(), List.copyOf(commandPlans)));
        }
    }

    public ObjectiveRegistry objectiveRegistry() {
        return objectiveRegistry;
    }

    public OptimizedSlotRegistry slotRegistry() {
        return slotRegistry;
    }

    public int preparedFunctionCount() {
        return preparedFunctions.size();
    }

    public List<Identifier> preparedFunctionIds() {
        return List.copyOf(preparedFunctions.keySet());
    }

    public @Nullable PreparedFunctionPlan getPreparedFunction(Identifier id) {
        return preparedFunctions.get(id);
    }

    private CommandLoweringPlan analyzeCommand(FunctionIrRegistry.CommandParseNode commandNode) {
        if (commandNode.controlFlowKind() != FunctionIrRegistry.ControlFlowKind.NONE) {
            return CommandLoweringPlan.of(CommandLoweringKind.CONTROL_FLOW, List.of(), List.of(commandNode.controlFlowKind().name()));
        }

        String sourceText = commandNode.sourceText();
        Matcher operationMatcher = SCOREBOARD_OPERATION_PATTERN.matcher(sourceText);
        if (operationMatcher.matches()) {
            Integer targetSlot = registerLiteralSlot(operationMatcher.group(1), operationMatcher.group(2));
            Integer sourceSlot = registerLiteralSlot(operationMatcher.group(4), operationMatcher.group(5));
            if (targetSlot != null && sourceSlot != null) {
                return CommandLoweringPlan.of(
                        CommandLoweringKind.SCOREBOARD_OPERATION,
                        List.of(targetSlot, sourceSlot),
                        List.of("op=" + operationMatcher.group(3))
                );
            }
            return CommandLoweringPlan.of(CommandLoweringKind.BRIDGE, List.of(), List.of("dynamic-scoreboard-operation"));
        }

        Matcher playerMatcher = SCOREBOARD_PLAYER_PATTERN.matcher(sourceText);
        if (playerMatcher.matches()) {
            Integer slotId = registerLiteralSlot(playerMatcher.group(2), playerMatcher.group(3));
            if (slotId == null) {
                return CommandLoweringPlan.of(CommandLoweringKind.BRIDGE, List.of(), List.of("dynamic-scoreboard-slot"));
            }

            CommandLoweringKind kind = switch (playerMatcher.group(1)) {
                case "get" -> CommandLoweringKind.SCOREBOARD_GET;
                case "set" -> CommandLoweringKind.SCOREBOARD_SET;
                case "add" -> CommandLoweringKind.SCOREBOARD_ADD;
                case "remove" -> CommandLoweringKind.SCOREBOARD_REMOVE;
                case "reset" -> CommandLoweringKind.SCOREBOARD_RESET;
                default -> CommandLoweringKind.BRIDGE;
            };
            return CommandLoweringPlan.of(kind, List.of(slotId), List.of());
        }

        return CommandLoweringPlan.of(CommandLoweringKind.BRIDGE, List.of(), List.of("opaque-command"));
    }

    private @Nullable Integer registerLiteralSlot(String holderName, String objectiveName) {
        if (!isLiteralHolder(holderName) || !isLiteralObjective(objectiveName)) {
            return null;
        }

        int objectiveId = objectiveRegistry.register(objectiveName);
        return slotRegistry.register(new SlotKey(holderName, objectiveName), objectiveId);
    }

    private static boolean isLiteralHolder(String token) {
        return !token.isEmpty() && token.charAt(0) != '@' && !token.startsWith("$(");
    }

    private static boolean isLiteralObjective(String token) {
        return !token.isEmpty() && token.charAt(0) != '@' && !token.startsWith("$(");
    }

    public record PreparedCommandPlan(
            int nodeIndex,
            String sourceText,
            CommandLoweringPlan loweringPlan
    ) {
    }

    public record PreparedFunctionPlan(
            Identifier id,
            List<PreparedCommandPlan> commandPlans
    ) {
    }
}
