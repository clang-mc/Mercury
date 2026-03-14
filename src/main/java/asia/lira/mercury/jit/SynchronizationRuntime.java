package asia.lira.mercury.jit;

import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class SynchronizationRuntime {
    private static final SynchronizationRuntime INSTANCE = new SynchronizationRuntime();

    private final Deque<ExecutionFrame> activeFrames = new ArrayDeque<>();

    private SynchronizationRuntime() {
    }

    public static SynchronizationRuntime getInstance() {
        return INSTANCE;
    }

    public void clear() {
        activeFrames.clear();
    }

    public void pushFrame(ExecutionFrame frame) {
        activeFrames.push(frame);
    }

    public void popFrame(ExecutionFrame frame) {
        if (activeFrames.isEmpty()) {
            return;
        }

        if (activeFrames.peek() == frame) {
            activeFrames.pop();
            frame.release();
            return;
        }

        activeFrames.remove(frame);
        frame.release();
    }

    public boolean hasActiveFrames() {
        return !activeFrames.isEmpty();
    }

    public ExecutionFrame currentFrame() {
        return activeFrames.peek();
    }

    public void onScoreUpdated(ScoreHolder holder, ScoreboardObjective objective, int value) {
        if (activeFrames.isEmpty()) {
            return;
        }

        OptimizedSlotRegistry slotRegistry = JitPreparationRegistry.getInstance().slotRegistry();
        Integer slotId = slotRegistry.getSlotId(holder.getNameForScoreboard(), objective.getName());
        if (slotId != null) {
            for (ExecutionFrame frame : activeFrames) {
                frame.onExternalSlotUpdated(slotId, value);
            }
            return;
        }

        invalidateObjective(objective.getName());
    }

    public void onScoreRemoved(ScoreHolder holder, ScoreboardObjective objective) {
        if (activeFrames.isEmpty()) {
            return;
        }

        OptimizedSlotRegistry slotRegistry = JitPreparationRegistry.getInstance().slotRegistry();
        Integer slotId = slotRegistry.getSlotId(holder.getNameForScoreboard(), objective.getName());
        if (slotId != null) {
            for (ExecutionFrame frame : activeFrames) {
                frame.invalidateSlot(slotId);
            }
            return;
        }

        invalidateObjective(objective.getName());
    }

    public void onScoreHolderRemoved(ScoreHolder holder) {
        if (activeFrames.isEmpty()) {
            return;
        }

        List<Integer> slots = JitPreparationRegistry.getInstance().slotRegistry().getSlotsForHolder(holder.getNameForScoreboard());
        if (slots.isEmpty()) {
            return;
        }

        for (ExecutionFrame frame : activeFrames) {
            frame.invalidateSlots(slots);
        }
    }

    public void onObjectiveRemoved(ScoreboardObjective objective) {
        if (activeFrames.isEmpty()) {
            return;
        }
        invalidateObjective(objective.getName());
    }

    private void invalidateObjective(String objectiveName) {
        List<Integer> slots = JitPreparationRegistry.getInstance().slotRegistry().getSlotsForObjective(objectiveName);
        if (slots.isEmpty()) {
            return;
        }

        for (ExecutionFrame frame : activeFrames) {
            frame.invalidateSlots(slots);
        }
    }
}
