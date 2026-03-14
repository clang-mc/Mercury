package asia.lira.mercury.jit;

import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.AbstractServerCommandSource;

import java.util.BitSet;

public final class BaselineCompiledAction<T extends AbstractServerCommandSource<T>> implements SourcedCommandAction<T> {
    private final BaselineCompiledFunctionRegistry.CompiledArtifact artifact;

    public BaselineCompiledAction(BaselineCompiledFunctionRegistry.CompiledArtifact artifact) {
        this.artifact = artifact;
    }

    @Override
    public void execute(T source, CommandExecutionContext<T> context, Frame frame) {
        SynchronizationRuntime runtime = SynchronizationRuntime.getInstance();
        ExecutionFrame current = runtime.currentFrame();
        boolean ownsFrame = false;
        if (current == null) {
            current = new ExecutionFrame(artifact.program().id(), JitPreparationRegistry.getInstance().slotRegistry().count());
            runtime.pushFrame(current);
            ownsFrame = true;
        }

        try {
            BaselineExecutionEngine.ensureLoaded(current, artifact.requiredSlots());
            BaselineExecutionEngine.ExecutionOutcome outcome = artifact.compiledFunction().invoke(current, source, context);
            flushDirtySlots(current);
            if (outcome.mode() == BaselineExecutionEngine.ExecutionOutcome.Mode.RETURN) {
                frame.succeed(outcome.returnValue());
                frame.doReturn();
            }
        } catch (Throwable throwable) {
            throw new RuntimeException("Failed to execute compiled function " + artifact.program().id(), throwable);
        } finally {
            if (ownsFrame) {
                runtime.popFrame(current);
            }
        }
    }

    private void flushDirtySlots(ExecutionFrame frame) {
        BitSet dirty = frame.dirtySlots();
        Scoreboard scoreboard = asia.lira.mercury.Mercury.SERVER.getScoreboard();

        for (int slotId = dirty.nextSetBit(0); slotId >= 0; slotId = dirty.nextSetBit(slotId + 1)) {
            OptimizedSlotRegistry.SlotMetadata metadata = JitPreparationRegistry.getInstance().slotRegistry().getSlot(slotId);
            if (metadata == null) {
                continue;
            }

            ScoreboardObjective objective = scoreboard.getNullableObjective(metadata.key().objectiveName());
            if (objective == null) {
                continue;
            }

            scoreboard.getOrCreateScore(ScoreHolder.fromName(metadata.key().holderName()), objective).setScore(frame.getSlotValue(slotId));
            frame.loadSlotValue(slotId, frame.getSlotValue(slotId));
        }
    }
}
