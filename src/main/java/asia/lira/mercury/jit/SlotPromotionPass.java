package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SlotPromotionPass implements BaselinePass {
    private static final int PROMOTED_LOCAL_START = 8;

    @Override
    public LoweredUnit apply(LoweredUnit unit, BaselinePassContext context) {
        Set<Integer> promotableSlots = collectPromotableSlots(unit, context);
        if (promotableSlots.isEmpty()) {
            return unit;
        }

        Map<Integer, Integer> promotedLocals = new LinkedHashMap<>();
        int nextLocalIndex = PROMOTED_LOCAL_START;
        for (int slotId : promotableSlots) {
            promotedLocals.put(slotId, nextLocalIndex++);
        }

        List<LoweredUnit.LoweredBlock> rewrittenBlocks = new ArrayList<>(unit.blocks().size());
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            rewrittenBlocks.add(rewriteBlock(block, promotableSlots, context));
        }

        return new LoweredUnit(
                unit.entryId(),
                rewrittenBlocks,
                unit.entryIndex(),
                unit.requiredSlots(),
                promotedLocals
        );
    }

    private Set<Integer> collectPromotableSlots(LoweredUnit unit, BaselinePassContext context) {
        Map<Integer, Integer> accessCounts = new LinkedHashMap<>();
        Set<Integer> resetSlots = new LinkedHashSet<>();

        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                switch (instruction) {
                    case LoweredUnit.SetConstInstruction setConst -> bump(accessCounts, setConst.slotId());
                    case LoweredUnit.AddConstInstruction addConst -> {
                        bump(accessCounts, addConst.slotId());
                        bump(accessCounts, addConst.slotId());
                    }
                    case LoweredUnit.GetInstruction get -> bump(accessCounts, get.slotId());
                    case LoweredUnit.ResetInstruction reset -> {
                        bump(accessCounts, reset.slotId());
                        resetSlots.add(reset.slotId());
                    }
                    case LoweredUnit.OperationInstruction operation -> {
                        String op = operation.operation();
                        if ("=".equals(op)) {
                            bump(accessCounts, operation.secondarySlot());
                            bump(accessCounts, operation.primarySlot());
                        } else if ("><".equals(op)) {
                            bump(accessCounts, operation.primarySlot());
                            bump(accessCounts, operation.secondarySlot());
                            bump(accessCounts, operation.primarySlot());
                            bump(accessCounts, operation.secondarySlot());
                        } else {
                            bump(accessCounts, operation.primarySlot());
                            bump(accessCounts, operation.secondarySlot());
                            bump(accessCounts, operation.primarySlot());
                        }
                    }
                    case LoweredUnit.CallInstruction ignored -> {
                    }
                    case LoweredUnit.ReflectiveBridgeInstruction ignored -> {
                    }
                    case LoweredUnit.ActionBridgeInstruction ignored -> {
                    }
                    case LoweredUnit.SpecializedInstruction ignored -> {
                    }
                }
            }
        }

        Set<Integer> promotable = new LinkedHashSet<>();
        for (int slotId : context.allPromotableSlots()) {
            if (resetSlots.contains(slotId)) {
                continue;
            }
            if (accessCounts.getOrDefault(slotId, 0) >= 3) {
                promotable.add(slotId);
            }
        }
        return promotable;
    }

    private LoweredUnit.LoweredBlock rewriteBlock(
            LoweredUnit.LoweredBlock block,
            Set<Integer> promotableSlots,
            BaselinePassContext context
    ) {
        List<LoweredUnit.LoweredInstruction> rewrittenInstructions = new ArrayList<>(block.instructions().size());
        for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
            if (instruction instanceof LoweredUnit.CallInstruction callInstruction) {
                SlotEffectSummary effectSummary = context.effectSummaries().get(callInstruction.targetFunction());
                rewrittenInstructions.add(new LoweredUnit.CallInstruction(
                        callInstruction.targetFunction(),
                        callInstruction.bindingId(),
                        collectSpillSlots(promotableSlots, effectSummary),
                        collectReloadSlots(promotableSlots, effectSummary),
                        callInstruction.sourceText()
                ));
                continue;
            }
            if (instruction instanceof LoweredUnit.ReflectiveBridgeInstruction reflectiveBridgeInstruction) {
                rewrittenInstructions.add(new LoweredUnit.ReflectiveBridgeInstruction(
                        reflectiveBridgeInstruction.bindingId(),
                        toIntArray(promotableSlots),
                        toIntArray(promotableSlots),
                        reflectiveBridgeInstruction.sourceText()
                ));
                continue;
            }
            if (instruction instanceof LoweredUnit.ActionBridgeInstruction actionBridgeInstruction) {
                rewrittenInstructions.add(new LoweredUnit.ActionBridgeInstruction(
                        actionBridgeInstruction.bindingId(),
                        toIntArray(promotableSlots),
                        toIntArray(promotableSlots),
                        actionBridgeInstruction.sourceText()
                ));
                continue;
            }
            if (instruction instanceof LoweredUnit.SpecializedInstruction specializedInstruction) {
                rewrittenInstructions.add(new LoweredUnit.SpecializedInstruction(
                        specializedInstruction.specializedId(),
                        toIntArray(promotableSlots),
                        toIntArray(promotableSlots),
                        specializedInstruction.sourceText()
                ));
                continue;
            }
            rewrittenInstructions.add(instruction);
        }

        LoweredUnit.LoweredTerminator terminator = block.terminator();
        if (terminator instanceof LoweredUnit.JumpExternalTerminator jumpExternalTerminator) {
            SlotEffectSummary effectSummary = context.effectSummaries().get(jumpExternalTerminator.targetFunction());
            terminator = new LoweredUnit.JumpExternalTerminator(
                    jumpExternalTerminator.targetFunction(),
                    jumpExternalTerminator.bindingId(),
                    effectSummary == null ? toIntArray(promotableSlots) : collectSpillSlots(promotableSlots, effectSummary)
            );
        } else if (terminator instanceof LoweredUnit.SuspendActionTerminator suspendActionTerminator) {
            terminator = new LoweredUnit.SuspendActionTerminator(
                    suspendActionTerminator.bindingId(),
                    suspendActionTerminator.continuationBlockIndex(),
                    toIntArray(promotableSlots)
            );
        }

        return new LoweredUnit.LoweredBlock(block.programId(), rewrittenInstructions, terminator);
    }

    private static int[] collectSpillSlots(Set<Integer> promotableSlots, SlotEffectSummary effectSummary) {
        if (effectSummary == null) {
            return toIntArray(promotableSlots);
        }

        List<Integer> spillSlots = new ArrayList<>();
        for (int slotId : promotableSlots) {
            if (effectSummary.reads(slotId) || effectSummary.writes(slotId)) {
                spillSlots.add(slotId);
            }
        }
        return spillSlots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] collectReloadSlots(Set<Integer> promotableSlots, SlotEffectSummary effectSummary) {
        if (effectSummary == null) {
            return toIntArray(promotableSlots);
        }

        List<Integer> reloadSlots = new ArrayList<>();
        for (int slotId : promotableSlots) {
            if (effectSummary.writes(slotId)) {
                reloadSlots.add(slotId);
            }
        }
        return reloadSlots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] toIntArray(Set<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void bump(Map<Integer, Integer> counts, int slotId) {
        counts.merge(slotId, 1, Integer::sum);
    }
}
