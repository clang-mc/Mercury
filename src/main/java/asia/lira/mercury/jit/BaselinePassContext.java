package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;

public record BaselinePassContext(
        Map<Identifier, String> internalNames,
        Map<Identifier, Integer> callSiteCounts,
        Map<Identifier, int[]> requiredSlotsById,
        Map<Identifier, SlotEffectSummary> effectSummaries,
        Set<Integer> allPromotableSlots
) {
}
