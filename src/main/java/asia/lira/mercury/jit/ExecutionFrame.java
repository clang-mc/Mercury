package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.Arrays;
import java.util.BitSet;

public final class ExecutionFrame {
    private final Identifier functionId;
    private final int[] slotValues;
    private final BitSet loadedSlots;
    private final BitSet dirtySlots;
    private final BitSet invalidSlots;
    private boolean released;

    public ExecutionFrame(Identifier functionId, int slotCount) {
        this.functionId = functionId;
        this.slotValues = new int[Math.max(0, slotCount)];
        this.loadedSlots = new BitSet(slotCount);
        this.dirtySlots = new BitSet(slotCount);
        this.invalidSlots = new BitSet(slotCount);
    }

    private ExecutionFrame(Identifier functionId, int[] slotValues, BitSet loadedSlots, BitSet dirtySlots, BitSet invalidSlots) {
        this.functionId = functionId;
        this.slotValues = slotValues;
        this.loadedSlots = loadedSlots;
        this.dirtySlots = dirtySlots;
        this.invalidSlots = invalidSlots;
    }

    public Identifier functionId() {
        return functionId;
    }

    public int slotCount() {
        return slotValues.length;
    }

    public boolean isReleased() {
        return released;
    }

    public void release() {
        this.released = true;
    }

    public boolean isLoaded(int slotId) {
        return loadedSlots.get(slotId) && !invalidSlots.get(slotId);
    }

    public int getSlotValue(int slotId) {
        return slotValues[slotId];
    }

    public void setSlotValue(int slotId, int value) {
        slotValues[slotId] = value;
        loadedSlots.set(slotId);
        dirtySlots.set(slotId);
        invalidSlots.clear(slotId);
    }

    public void loadSlotValue(int slotId, int value) {
        slotValues[slotId] = value;
        loadedSlots.set(slotId);
        dirtySlots.clear(slotId);
        invalidSlots.clear(slotId);
    }

    public void onExternalSlotUpdated(int slotId, int value) {
        slotValues[slotId] = value;
        loadedSlots.set(slotId);
        dirtySlots.clear(slotId);
        invalidSlots.clear(slotId);
    }

    public void invalidateSlot(int slotId) {
        loadedSlots.clear(slotId);
        dirtySlots.clear(slotId);
        invalidSlots.set(slotId);
    }

    public void invalidateSlots(Iterable<Integer> slotIds) {
        for (int slotId : slotIds) {
            invalidateSlot(slotId);
        }
    }

    public BitSet dirtySlots() {
        return (BitSet) dirtySlots.clone();
    }

    public ExecutionFrame fork() {
        return new ExecutionFrame(
                functionId,
                Arrays.copyOf(slotValues, slotValues.length),
                (BitSet) loadedSlots.clone(),
                (BitSet) dirtySlots.clone(),
                (BitSet) invalidSlots.clone()
        );
    }
}
