package asia.lira.mercury.jit;

import asia.lira.mercury.Mercury;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtElement;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.ServerCommandSource;
import org.jetbrains.annotations.Nullable;

public final class SpecializedRuntime {
    private SpecializedRuntime() {
    }

    public static void invoke(int specializedId, ExecutionFrame frame, ServerCommandSource source) throws Exception {
        SpecializedCommandRegistry.SpecializedPlan plan = SpecializedCommandRegistry.getInstance().plan(specializedId);
        if (plan == null) {
            throw new IllegalStateException("Missing specialized plan " + specializedId);
        }
        if (plan instanceof SpecializedCommandRegistry.ExecutePlan executePlan) {
            ExecuteRuntime.invoke(executePlan, frame, source);
            return;
        }
        executeTerminal(plan, frame, source);
    }

    public static ExecuteRuntime.CommandResult executeTerminal(
            SpecializedCommandRegistry.SpecializedPlan plan,
            ExecutionFrame frame,
            ServerCommandSource source
    ) throws Exception {
        return switch (plan) {
            case SpecializedCommandRegistry.ScoreSetPlan scoreSetPlan -> {
                writeScore(frame, source, scoreSetPlan.holder(), scoreSetPlan.objective(), scoreSetPlan.value());
                yield new ExecuteRuntime.CommandResult(true, scoreSetPlan.value());
            }
            case SpecializedCommandRegistry.ScoreAddPlan scoreAddPlan -> {
                int next = readScore(frame, source, scoreAddPlan.holder(), scoreAddPlan.objective()) + scoreAddPlan.delta();
                writeScore(frame, source, scoreAddPlan.holder(), scoreAddPlan.objective(), next);
                yield new ExecuteRuntime.CommandResult(true, next);
            }
            case SpecializedCommandRegistry.ScoreGetPlan scoreGetPlan ->
                    new ExecuteRuntime.CommandResult(true, readScore(frame, source, scoreGetPlan.holder(), scoreGetPlan.objective()));
            case SpecializedCommandRegistry.ScoreResetPlan scoreResetPlan -> {
                resetScore(frame, source, scoreResetPlan.holder(), scoreResetPlan.objective());
                yield new ExecuteRuntime.CommandResult(true, 1);
            }
            case SpecializedCommandRegistry.ScoreOperationPlan scoreOperationPlan -> {
                int left = readScore(frame, source, scoreOperationPlan.targetHolder(), scoreOperationPlan.targetObjective());
                int right = readScore(frame, source, scoreOperationPlan.sourceHolder(), scoreOperationPlan.sourceObjective());
                int result = applyOperation(left, right, scoreOperationPlan.operation());
                writeScore(frame, source, scoreOperationPlan.targetHolder(), scoreOperationPlan.targetObjective(), result);
                if ("><".equals(scoreOperationPlan.operation())) {
                    writeScore(frame, source, scoreOperationPlan.sourceHolder(), scoreOperationPlan.sourceObjective(), left);
                }
                yield new ExecuteRuntime.CommandResult(true, result);
            }
            case SpecializedCommandRegistry.DataModifyStorageSetValuePlan setValuePlan ->
                    new ExecuteRuntime.CommandResult(true, StorageAccessRuntime.setValue(setValuePlan.targetStorageId(), setValuePlan.targetPath(), setValuePlan.value()));
            case SpecializedCommandRegistry.DataModifyStorageSetFromPlan setFromPlan ->
                    new ExecuteRuntime.CommandResult(true, StorageAccessRuntime.setFromStorage(setFromPlan.targetStorageId(), setFromPlan.targetPath(), setFromPlan.sourceStorageId(), setFromPlan.sourcePath()));
            case SpecializedCommandRegistry.DataModifyStorageMergeValuePlan mergeValuePlan ->
                    new ExecuteRuntime.CommandResult(true, StorageAccessRuntime.mergeValue(mergeValuePlan.targetStorageId(), mergeValuePlan.targetPath(), mergeValuePlan.value()));
            case SpecializedCommandRegistry.DataModifyStorageMergeFromPlan mergeFromPlan ->
                    new ExecuteRuntime.CommandResult(true, StorageAccessRuntime.mergeFromStorage(mergeFromPlan.targetStorageId(), mergeFromPlan.targetPath(), mergeFromPlan.sourceStorageId(), mergeFromPlan.sourcePath()));
            case SpecializedCommandRegistry.ExecutePlan executePlan -> throw new IllegalStateException("Nested execute plan " + executePlan.sourceText());
        };
    }

    public static int readScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            return BaselineExecutionEngine.readSlot(frame, slotId);
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective == null) {
            return 0;
        }
        ReadableScoreboardScore score = scoreboard.getScore(ScoreHolder.fromName(holder), targetObjective);
        return score == null ? 0 : score.getScore();
    }

    public static void writeScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective, int value) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            frame.setSlotValue(slotId, value);
            return;
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective == null) {
            return;
        }
        ScoreAccess access = scoreboard.getOrCreateScore(ScoreHolder.fromName(holder), targetObjective);
        access.setScore(value);
    }

    public static void resetScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            BaselineExecutionEngine.resetSlot(frame, slotId);
            return;
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective != null) {
            scoreboard.removeScore(ScoreHolder.fromName(holder), targetObjective);
        }
    }

    private static int applyOperation(int left, int right, String operation) {
        return switch (operation) {
            case "=" -> right;
            case "+=" -> left + right;
            case "-=" -> left - right;
            case "*=" -> left * right;
            case "/=" -> right == 0 ? 0 : left / right;
            case "%=" -> right == 0 ? 0 : left % right;
            case "<" -> Math.min(left, right);
            case ">" -> Math.max(left, right);
            case "><" -> right;
            default -> throw new IllegalArgumentException("Unsupported scoreboard operation " + operation);
        };
    }
}
