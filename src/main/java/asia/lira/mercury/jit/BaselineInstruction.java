package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public record BaselineInstruction(
        OpCode opCode,
        int primarySlot,
        int secondarySlot,
        int immediate,
        @Nullable Identifier targetFunction,
        @Nullable String operation,
        String sourceText
) {
    public static BaselineInstruction set(int slotId, int value, String sourceText) {
        return new BaselineInstruction(OpCode.SET_CONST, slotId, -1, value, null, null, sourceText);
    }

    public static BaselineInstruction add(int slotId, int delta, String sourceText) {
        return new BaselineInstruction(OpCode.ADD_CONST, slotId, -1, delta, null, null, sourceText);
    }

    public static BaselineInstruction get(int slotId, String sourceText) {
        return new BaselineInstruction(OpCode.GET, slotId, -1, 0, null, null, sourceText);
    }

    public static BaselineInstruction reset(int slotId, String sourceText) {
        return new BaselineInstruction(OpCode.RESET, slotId, -1, 0, null, null, sourceText);
    }

    public static BaselineInstruction operation(int targetSlot, int sourceSlot, String operation, String sourceText) {
        return new BaselineInstruction(OpCode.OPERATION, targetSlot, sourceSlot, 0, null, operation, sourceText);
    }

    public static BaselineInstruction call(Identifier targetFunction, String sourceText) {
        return new BaselineInstruction(OpCode.CALL, -1, -1, 0, targetFunction, null, sourceText);
    }

    public static BaselineInstruction jump(Identifier targetFunction, String sourceText) {
        return new BaselineInstruction(OpCode.JUMP, -1, -1, 0, targetFunction, null, sourceText);
    }

    public static BaselineInstruction returnValue(int returnValue, String sourceText) {
        return new BaselineInstruction(OpCode.RETURN_VALUE, -1, -1, returnValue, null, null, sourceText);
    }

    public enum OpCode {
        SET_CONST,
        ADD_CONST,
        GET,
        RESET,
        OPERATION,
        CALL,
        JUMP,
        RETURN_VALUE
    }
}
