package asia.lira.mercury.jit.dump;

import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import asia.lira.mercury.jit.registry.OptimizedSlotRegistry;

import java.util.ArrayList;
import java.util.List;

public final class JitPreparationDumper {
    private JitPreparationDumper() {
    }

    public static List<String> dumpPrepared(JitPreparationRegistry.PreparedFunctionPlan functionPlan, OptimizedSlotRegistry slotRegistry) {
        List<String> lines = new ArrayList<>();
        lines.add("Prepared JIT plan: " + functionPlan.id());

        for (JitPreparationRegistry.PreparedCommandPlan commandPlan : functionPlan.commandPlans()) {
            lines.add("[" + commandPlan.nodeIndex() + "] kind=" + commandPlan.loweringPlan().kind() + " text=" + commandPlan.sourceText());
            if (!commandPlan.loweringPlan().slotIds().isEmpty()) {
                List<String> resolvedSlots = new ArrayList<>(commandPlan.loweringPlan().slotIds().size());
                for (int slotId : commandPlan.loweringPlan().slotIds()) {
                    OptimizedSlotRegistry.SlotMetadata metadata = slotRegistry.getSlot(slotId);
                    if (metadata == null) {
                        resolvedSlots.add(slotId + ":<missing>");
                        continue;
                    }
                    resolvedSlots.add(slotId + ":" + metadata.key().holderName() + "/" + metadata.key().objectiveName());
                }
                lines.add("    slots=" + resolvedSlots);
            }
            if (!commandPlan.loweringPlan().notes().isEmpty()) {
                lines.add("    notes=" + commandPlan.loweringPlan().notes());
            }
        }

        return lines;
    }
}
