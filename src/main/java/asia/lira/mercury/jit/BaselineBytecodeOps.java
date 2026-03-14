package asia.lira.mercury.jit;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class BaselineBytecodeOps {
    private static final String FRAME_INTERNAL = Type.getInternalName(ExecutionFrame.class);
    private static final String RUNTIME_INTERNAL = Type.getInternalName(BaselineExecutionEngine.class);
    private static final String OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
    private static final String CONTEXT_DESC = Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class);

    private BaselineBytecodeOps() {
    }

    public static void buildSetConst(MethodVisitor visitor, int slotId, int value) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, slotId);
        pushInt(visitor, value);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildGet(MethodVisitor visitor, int slotId) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitInsn(Opcodes.POP);
    }

    public static void buildAddConst(MethodVisitor visitor, int slotId, int delta) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, slotId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        pushInt(visitor, delta);
        visitor.visitInsn(Opcodes.IADD);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildReset(MethodVisitor visitor, int slotId) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "resetSlot", "(L" + FRAME_INTERNAL + ";I)V", false);
    }

    public static void buildOperation(MethodVisitor visitor, int primarySlot, int secondarySlot, String operation) {
        switch (operation) {
            case "=" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 1);
                pushInt(visitor, primarySlot);
                visitor.visitVarInsn(Opcodes.ALOAD, 1);
                pushInt(visitor, secondarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
            }
            case "+=", "-=", "*=", "/=", "%=", "<", ">" -> buildBinaryOperation(visitor, primarySlot, secondarySlot, operation);
            case "><" -> buildSwap(visitor, primarySlot, secondarySlot);
            default -> throw new IllegalArgumentException("Unsupported scoreboard operation: " + operation);
        }
    }

    public static void buildCall(MethodVisitor visitor, String functionId) {
        visitor.visitLdcInsn(functionId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "invokeCompiled",
                "(Ljava/lang/String;L" + FRAME_INTERNAL + ";Ljava/lang/Object;" + CONTEXT_DESC + ")" + OUTCOME_DESC,
                false
        );
        visitor.visitInsn(Opcodes.POP);
    }

    public static void buildExternalJump(MethodVisitor visitor, String functionId) {
        visitor.visitLdcInsn(functionId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "invokeCompiled",
                "(Ljava/lang/String;L" + FRAME_INTERNAL + ";Ljava/lang/Object;" + CONTEXT_DESC + ")" + OUTCOME_DESC,
                false
        );
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildReturnValue(MethodVisitor visitor, int returnValue) {
        pushInt(visitor, returnValue);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "returnValue",
                "(I)" + OUTCOME_DESC,
                false
        );
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildCompleted(MethodVisitor visitor) {
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "completed", "()" + OUTCOME_DESC, false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    private static void buildBinaryOperation(MethodVisitor visitor, int primarySlot, int secondarySlot, String operation) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, primarySlot);

        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, primarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);

        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, secondarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);

        switch (operation) {
            case "+=" -> visitor.visitInsn(Opcodes.IADD);
            case "-=" -> visitor.visitInsn(Opcodes.ISUB);
            case "*=" -> visitor.visitInsn(Opcodes.IMUL);
            case "/=" -> buildSafeDivide(visitor, Opcodes.IDIV);
            case "%=" -> buildSafeDivide(visitor, Opcodes.IREM);
            case "<" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
            case ">" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
            default -> throw new IllegalArgumentException("Unsupported binary operation: " + operation);
        }

        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    private static void buildSwap(MethodVisitor visitor, int primarySlot, int secondarySlot) {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, primarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, 5);

        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, secondarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, 6);

        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, primarySlot);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);

        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        pushInt(visitor, secondarySlot);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    private static void buildSafeDivide(MethodVisitor visitor, int opcode) {
        Label divisorNonZero = new Label();
        Label done = new Label();
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitJumpInsn(Opcodes.IFNE, divisorNonZero);
        visitor.visitInsn(Opcodes.POP2);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitJumpInsn(Opcodes.GOTO, done);
        visitor.visitLabel(divisorNonZero);
        visitor.visitInsn(opcode);
        visitor.visitLabel(done);
    }

    public static void pushInt(MethodVisitor visitor, int value) {
        switch (value) {
            case -1 -> visitor.visitInsn(Opcodes.ICONST_M1);
            case 0 -> visitor.visitInsn(Opcodes.ICONST_0);
            case 1 -> visitor.visitInsn(Opcodes.ICONST_1);
            case 2 -> visitor.visitInsn(Opcodes.ICONST_2);
            case 3 -> visitor.visitInsn(Opcodes.ICONST_3);
            case 4 -> visitor.visitInsn(Opcodes.ICONST_4);
            case 5 -> visitor.visitInsn(Opcodes.ICONST_5);
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    visitor.visitIntInsn(Opcodes.BIPUSH, value);
                } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                    visitor.visitIntInsn(Opcodes.SIPUSH, value);
                } else {
                    visitor.visitLdcInsn(value);
                }
            }
        }
    }
}
