package asia.lira.mercury.jit;

import asia.lira.mercury.Mercury;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.AbstractServerCommandSource;

public final class BaselineExecutionEngine {
    private BaselineExecutionEngine() {
    }

    public static <T extends AbstractServerCommandSource<T>> ExecutionOutcome execute(
            BaselineProgram program,
            ExecutionFrame frame,
            T source,
            CommandExecutionContext<T> context
    ) {
        BaselineProgram currentProgram = program;

        while (true) {
            for (BaselineInstruction instruction : currentProgram.instructions()) {
                context.decrementCommandQuota();
                switch (instruction.opCode()) {
                    case SET_CONST -> writeSlot(frame, instruction.primarySlot(), instruction.immediate());
                    case ADD_CONST -> writeSlot(frame, instruction.primarySlot(), readSlot(frame, instruction.primarySlot()) + instruction.immediate());
                    case GET -> readSlot(frame, instruction.primarySlot());
                    case RESET -> resetSlot(frame, instruction.primarySlot());
                    case OPERATION -> runOperation(frame, instruction);
                    case CALL -> {
                        BaselineProgram callee = BaselineCompiledFunctionRegistry.getInstance().get(instruction.targetFunction());
                        if (callee == null) {
                            return ExecutionOutcome.fallback();
                        }
                        ExecutionOutcome outcome = execute(callee, frame, source, context);
                        if (outcome.mode() == ExecutionOutcome.Mode.FALLBACK) {
                            return outcome;
                        }
                    }
                    case JUMP -> {
                        BaselineProgram jumpTarget = BaselineCompiledFunctionRegistry.getInstance().get(instruction.targetFunction());
                        if (jumpTarget == null) {
                            return ExecutionOutcome.fallback();
                        }
                        currentProgram = jumpTarget;
                        continue;
                    }
                    case RETURN_VALUE -> {
                        return ExecutionOutcome.returnValue(instruction.immediate());
                    }
                }
            }
            return ExecutionOutcome.completed();
        }
    }

    private static void runOperation(ExecutionFrame frame, BaselineInstruction instruction) {
        int left = readSlot(frame, instruction.primarySlot());
        int right = readSlot(frame, instruction.secondarySlot());
        int result = switch (instruction.operation()) {
            case "=" -> right;
            case "+=" -> left + right;
            case "-=" -> left - right;
            case "*=" -> left * right;
            case "/=" -> right == 0 ? 0 : left / right;
            case "%=" -> right == 0 ? 0 : left % right;
            case "<" -> Math.min(left, right);
            case ">" -> Math.max(left, right);
            case "><" -> right;
            default -> throw new IllegalStateException("Unexpected scoreboard operation: " + instruction.operation());
        };

        if ("><".equals(instruction.operation())) {
            writeSlot(frame, instruction.secondarySlot(), left);
        }
        writeSlot(frame, instruction.primarySlot(), result);
    }

    private static int readSlot(ExecutionFrame frame, int slotId) {
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

    private static void writeSlot(ExecutionFrame frame, int slotId, int value) {
        frame.setSlotValue(slotId, value);
    }

    private static void resetSlot(ExecutionFrame frame, int slotId) {
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
