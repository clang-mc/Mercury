package asia.lira.mercury.jit;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Map;
import java.util.Set;

public final class BaselineBytecodeCompiler {
    private static final String EXECUTION_FRAME_DESC = Type.getDescriptor(ExecutionFrame.class);
    private static final String OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
    public static final String REQUIRED_SLOTS_FIELD = "REQUIRED_SLOTS";

    private BaselineBytecodeCompiler() {
    }

    public static CompiledClassData compile(
            BaselineProgram program,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            int[] requiredSlots,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        String internalName = internalNameFor(program.id());
        byte[] classBytes = generateClass(program, internalName, unit, requiredSlots, internalNames, callSiteCounts, requiredSlotsById, classesWithSharedRequiredSlots);
        return new CompiledClassData(program.id(), internalName, classBytes, requiredSlots);
    }

    public static String internalNameFor(net.minecraft.util.Identifier id) {
        String namespace = sanitize(id.getNamespace());
        String path = sanitize(id.getPath());
        return "asia/lira/mercury/jit/Generated_" + namespace + "_" + path + "_" + Integer.toHexString(id.hashCode());
    }

    private static byte[] generateClass(
            BaselineProgram program,
            String internalName,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            int[] requiredSlots,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);

        boolean emitRequiredSlotsField = classesWithSharedRequiredSlots.contains(program.id());
        if (emitRequiredSlotsField) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, REQUIRED_SLOTS_FIELD, "[I", null, null).visitEnd();
        }

        emitConstructor(writer);
        if (emitRequiredSlotsField) {
            emitClassInitializer(writer, internalName, requiredSlots);
        }
        emitInvoke(writer, unit, internalNames, callSiteCounts, requiredSlotsById);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitClassInitializer(ClassWriter writer, String internalName, int[] requiredSlots) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        visitor.visitCode();
        BaselineBytecodeOps.buildIntArray(visitor, requiredSlots);
        visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, REQUIRED_SLOTS_FIELD, "[I");
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitInvoke(
            ClassWriter writer,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById
    ) {
        String descriptor = "(" + EXECUTION_FRAME_DESC + "Ljava/lang/Object;" + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class) + ")" + OUTCOME_DESC;
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", descriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();
        BaselineBytecodeOps.pushInt(visitor, unit.entryIndex());
        visitor.visitVarInsn(Opcodes.ISTORE, 3);

        Label loopStart = new Label();
        Label defaultLabel = new Label();
        Label[] stateLabels = new Label[unit.programs().size()];
        for (int i = 0; i < stateLabels.length; i++) {
            stateLabels[i] = new Label();
        }

        visitor.visitLabel(loopStart);
        visitor.visitVarInsn(Opcodes.ILOAD, 3);
        visitor.visitTableSwitchInsn(0, stateLabels.length - 1, defaultLabel, stateLabels);

        for (int i = 0; i < unit.programs().size(); i++) {
            visitor.visitLabel(stateLabels[i]);
            emitProgramBody(visitor, unit, unit.programs().get(i), loopStart, internalNames, callSiteCounts, requiredSlotsById);
        }

        visitor.visitLabel(defaultLabel);
        BaselineBytecodeOps.buildCompleted(visitor);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitProgramBody(
            MethodVisitor visitor,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            BaselineProgram program,
            Label loopStart,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById
    ) {
        for (BaselineInstruction instruction : program.instructions()) {
            switch (instruction.opCode()) {
                case SET_CONST -> BaselineBytecodeOps.buildSetConst(visitor, instruction.primarySlot(), instruction.immediate());
                case ADD_CONST -> BaselineBytecodeOps.buildAddConst(visitor, instruction.primarySlot(), instruction.immediate());
                case GET -> BaselineBytecodeOps.buildGet(visitor, instruction.primarySlot());
                case RESET -> BaselineBytecodeOps.buildReset(visitor, instruction.primarySlot());
                case OPERATION -> BaselineBytecodeOps.buildOperation(visitor, instruction.primarySlot(), instruction.secondarySlot(), instruction.operation());
                case CALL -> emitDirectInvoke(visitor, instruction.targetFunction(), internalNames, callSiteCounts, requiredSlotsById, false);
                case JUMP -> {
                    int localJumpIndex = unit.indexOf(instruction.targetFunction());
                    if (localJumpIndex >= 0) {
                        BaselineBytecodeOps.pushInt(visitor, localJumpIndex);
                        visitor.visitVarInsn(Opcodes.ISTORE, 3);
                        visitor.visitJumpInsn(Opcodes.GOTO, loopStart);
                    } else {
                        emitDirectInvoke(visitor, instruction.targetFunction(), internalNames, callSiteCounts, requiredSlotsById, true);
                    }
                }
                case RETURN_VALUE -> BaselineBytecodeOps.buildReturnValue(visitor, instruction.immediate());
            }
        }
        BaselineBytecodeOps.buildCompleted(visitor);
    }

    private static void emitDirectInvoke(
            MethodVisitor visitor,
            net.minecraft.util.Identifier targetFunction,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            boolean returnOutcome
    ) {
        String targetInternalName = internalNames.get(targetFunction);
        if (targetInternalName == null) {
            if (returnOutcome) {
                BaselineBytecodeOps.buildExternalJump(visitor, targetFunction.toString());
            } else {
                BaselineBytecodeOps.buildCall(visitor, targetFunction.toString());
            }
            return;
        }

        int[] requiredSlots = requiredSlotsById.getOrDefault(targetFunction, new int[0]);
        int callSites = callSiteCounts.getOrDefault(targetFunction, 0);
        if (shouldInlineRequiredSlots(requiredSlots.length, callSites)) {
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, requiredSlots);
        } else {
            BaselineBytecodeOps.buildEnsureLoadedFromStaticField(visitor, targetInternalName, REQUIRED_SLOTS_FIELD);
        }
        BaselineBytecodeOps.buildStaticInvoke(visitor, targetInternalName, returnOutcome);
    }

    public static boolean shouldInlineRequiredSlots(int slotCount, int callSites) {
        if (slotCount == 0) {
            return true;
        }
        if (callSites <= 1) {
            return true;
        }
        return 2 * slotCount * callSites - (3 * slotCount + 2 * callSites) < 10;
    }

    private static String sanitize(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    public record CompiledClassData(
            net.minecraft.util.Identifier id,
            String internalName,
            byte[] classBytes,
            int[] requiredSlots
    ) {
    }
}
