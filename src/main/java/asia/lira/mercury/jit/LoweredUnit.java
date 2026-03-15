package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LoweredUnit(
        Identifier entryId,
        List<LoweredBlock> blocks,
        int entryIndex,
        int[] requiredSlots,
        Map<Integer, Integer> promotedSlotLocals
) {
    public LoweredUnit {
        promotedSlotLocals = Map.copyOf(new LinkedHashMap<>(promotedSlotLocals));
        blocks = List.copyOf(blocks);
    }

    public boolean isPromoted(int slotId) {
        return promotedSlotLocals.containsKey(slotId);
    }

    public int localIndexFor(int slotId) {
        Integer localIndex = promotedSlotLocals.get(slotId);
        if (localIndex == null) {
            throw new IllegalArgumentException("Slot " + slotId + " is not promoted in " + entryId);
        }
        return localIndex;
    }

    public record LoweredBlock(
            Identifier programId,
            List<LoweredInstruction> instructions,
            LoweredTerminator terminator
    ) {
        public LoweredBlock {
            instructions = List.copyOf(instructions);
        }
    }

    public sealed interface LoweredInstruction permits SetConstInstruction, AddConstInstruction, GetInstruction, ResetInstruction, OperationInstruction, CallInstruction {
        String sourceText();
    }

    public record SetConstInstruction(int slotId, int value, String sourceText) implements LoweredInstruction {
    }

    public record AddConstInstruction(int slotId, int delta, String sourceText) implements LoweredInstruction {
    }

    public record GetInstruction(int slotId, String sourceText) implements LoweredInstruction {
    }

    public record ResetInstruction(int slotId, String sourceText) implements LoweredInstruction {
    }

    public record OperationInstruction(int primarySlot, int secondarySlot, String operation, String sourceText) implements LoweredInstruction {
    }

    public record CallInstruction(
            Identifier targetFunction,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public CallInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public sealed interface LoweredTerminator permits CompleteTerminator, ReturnValueTerminator, JumpLocalTerminator, JumpExternalTerminator {
    }

    public record CompleteTerminator() implements LoweredTerminator {
    }

    public record ReturnValueTerminator(int returnValue) implements LoweredTerminator {
    }

    public record JumpLocalTerminator(int targetBlockIndex) implements LoweredTerminator {
    }

    public record JumpExternalTerminator(
            Identifier targetFunction,
            int[] spillBeforeSlots
    ) implements LoweredTerminator {
        public JumpExternalTerminator {
            spillBeforeSlots = spillBeforeSlots.clone();
        }
    }
}
