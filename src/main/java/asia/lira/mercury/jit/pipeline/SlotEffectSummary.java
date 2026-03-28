package asia.lira.mercury.jit.pipeline;

import java.util.LinkedHashSet;
import java.util.Set;

public record SlotEffectSummary(
        Set<Integer> readSlots,
        Set<Integer> writtenSlots,
        Set<Integer> resetSlots
) {
    public SlotEffectSummary {
        readSlots = Set.copyOf(new LinkedHashSet<>(readSlots));
        writtenSlots = Set.copyOf(new LinkedHashSet<>(writtenSlots));
        resetSlots = Set.copyOf(new LinkedHashSet<>(resetSlots));
    }

    public boolean reads(int slotId) {
        return readSlots.contains(slotId);
    }

    public boolean writes(int slotId) {
        return writtenSlots.contains(slotId) || resetSlots.contains(slotId);
    }

    public boolean touches(int slotId) {
        return reads(slotId) || writes(slotId);
    }
}
