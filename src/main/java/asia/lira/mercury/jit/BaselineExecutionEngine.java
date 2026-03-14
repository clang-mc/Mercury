package asia.lira.mercury.jit;

import asia.lira.mercury.Mercury;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;

@SuppressWarnings("unused")
public final class BaselineExecutionEngine {
    private BaselineExecutionEngine() {
    }

    public static void opGet(ExecutionFrame frame, int slotId) {
        readSlot(frame, slotId);
    }

    public static void opReset(ExecutionFrame frame, int slotId) {
        resetSlot(frame, slotId);
    }

    public static void opOperation(ExecutionFrame frame, int primarySlot, int secondarySlot, String operation) {
        applyOperation(frame, primarySlot, secondarySlot, operation);
    }

    public static void opCall(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        ExecutionOutcome outcome = invokeCompiled(Identifier.of(functionId), frame, source, context);
        if (outcome.mode() == ExecutionOutcome.Mode.FALLBACK) {
            throw new IllegalStateException("Missing compiled callee for " + functionId);
        }
    }

    public static ExecutionOutcome opJump(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        return invokeCompiled(Identifier.of(functionId), frame, source, context);
    }

    public static ExecutionOutcome completed() {
        return ExecutionOutcome.completed();
    }

    public static ExecutionOutcome returnValue(int returnValue) {
        return ExecutionOutcome.returnValue(returnValue);
    }

    public static ExecutionOutcome invokeCompiled(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        return invokeCompiled(Identifier.of(functionId), frame, source, context);
    }

    public static void ensureLoaded(ExecutionFrame frame, int[] slotIds) {
        for (int slotId : slotIds) {
            readSlot(frame, slotId);
        }
    }

    public static int readSlot(ExecutionFrame frame, int slotId) {
        if (frame.isLoaded(slotId)) {
            return frame.getSlotValue(slotId);
        }

        OptimizedSlotRegistry.SlotMetadata metadata = JitPreparationRegistry.getInstance().slotRegistry().getSlot(slotId);
        if (metadata == null) {
            return 0;
        }

        Scoreboard scoreboard = Mercury.SERVER.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(metadata.key().objectiveName());
        if (objective == null) {
            frame.loadSlotValue(slotId, 0);
            return 0;
        }

        ReadableScoreboardScore score = scoreboard.getScore(ScoreHolder.fromName(metadata.key().holderName()), objective);
        int value = score == null ? 0 : score.getScore();
        frame.loadSlotValue(slotId, value);
        return value;
    }

    public static void resetSlot(ExecutionFrame frame, int slotId) {
        frame.invalidateSlot(slotId);

        OptimizedSlotRegistry.SlotMetadata metadata = JitPreparationRegistry.getInstance().slotRegistry().getSlot(slotId);
        if (metadata == null) {
            return;
        }

        Scoreboard scoreboard = Mercury.SERVER.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(metadata.key().objectiveName());
        if (objective == null) {
            return;
        }
        scoreboard.removeScore(ScoreHolder.fromName(metadata.key().holderName()), objective);
    }

    public static void applyOperation(ExecutionFrame frame, int primarySlot, int secondarySlot, String operation) {
        int left = readSlot(frame, primarySlot);
        int right = readSlot(frame, secondarySlot);
        int result = switch (operation) {
            case "=", "><" -> right;
            case "+=" -> left + right;
            case "-=" -> left - right;
            case "*=" -> left * right;
            case "/=" -> right == 0 ? 0 : left / right;
            case "%=" -> right == 0 ? 0 : left % right;
            case "<" -> Math.min(left, right);
            case ">" -> Math.max(left, right);
            default -> throw new IllegalStateException("Unexpected scoreboard operation: " + operation);
        };

        if ("><".equals(operation)) {
            frame.setSlotValue(secondarySlot, left);
        }
        frame.setSlotValue(primarySlot, result);
    }

    private static ExecutionOutcome invokeCompiled(Identifier id, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().getArtifact(id);
        if (artifact == null) {
            return ExecutionOutcome.fallback();
        }
        ensureLoaded(frame, artifact.requiredSlots());
        return artifact.compiledFunction().invoke(frame, source, context);
    }

    public record ExecutionOutcome(
            Mode mode,
            int returnValue
    ) {
        public static ExecutionOutcome completed() {
            return new ExecutionOutcome(Mode.COMPLETE, 0);
        }

        public static ExecutionOutcome returnValue(int returnValue) {
            return new ExecutionOutcome(Mode.RETURN, returnValue);
        }

        public static ExecutionOutcome fallback() {
            return new ExecutionOutcome(Mode.FALLBACK, 0);
        }

        public enum Mode {
            COMPLETE,
            RETURN,
            FALLBACK
        }
    }
}
