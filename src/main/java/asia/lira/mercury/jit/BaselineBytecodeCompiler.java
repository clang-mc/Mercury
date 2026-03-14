package asia.lira.mercury.jit;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;

public final class BaselineBytecodeCompiler {
    private static final String COMPILED_FUNCTION_INTERNAL = Type.getInternalName(CompiledFunction.class);
    private static final String EXECUTION_FRAME_DESC = Type.getDescriptor(ExecutionFrame.class);

    private BaselineBytecodeCompiler() {
    }

    public static BaselineCompiledFunctionRegistry.CompiledArtifact compile(
            BaselineProgram program,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            int[] requiredSlots
    ) {
        String internalName = generatedInternalName(program.id());
        byte[] classBytes = generateClass(program, internalName, unit);

        try {
            MethodHandles.Lookup hiddenLookup = MethodHandles.lookup().defineHiddenClass(classBytes, true);
            Class<?> hiddenClass = hiddenLookup.lookupClass();
            CompiledFunction compiledFunction = (CompiledFunction) hiddenClass.getDeclaredConstructor().newInstance();
            return new BaselineCompiledFunctionRegistry.CompiledArtifact(program, compiledFunction, classBytes, internalName, requiredSlots);
        } catch (Throwable throwable) {
            throw new RuntimeException("Failed to define compiled class for " + program.id(), throwable);
        }
    }

    private static byte[] generateClass(BaselineProgram program, String internalName, BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", new String[]{COMPILED_FUNCTION_INTERNAL});

        emitConstructor(writer);
        emitInvoke(writer, unit);

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

    private static void emitInvoke(ClassWriter writer, BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit) {
        String descriptor = "(" + EXECUTION_FRAME_DESC + "Ljava/lang/Object;" + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class) + ")" + Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke", descriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();
        BaselineBytecodeOps.pushInt(visitor, unit.entryIndex());
        visitor.visitVarInsn(Opcodes.ISTORE, 4);

        Label loopStart = new Label();
        Label defaultLabel = new Label();
        Label[] stateLabels = new Label[unit.programs().size()];
        for (int i = 0; i < stateLabels.length; i++) {
            stateLabels[i] = new Label();
        }

        visitor.visitLabel(loopStart);
        visitor.visitVarInsn(Opcodes.ILOAD, 4);
        visitor.visitTableSwitchInsn(0, stateLabels.length - 1, defaultLabel, stateLabels);

        for (int i = 0; i < unit.programs().size(); i++) {
            visitor.visitLabel(stateLabels[i]);
            emitProgramBody(visitor, unit, unit.programs().get(i), loopStart);
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
            Label loopStart
    ) {
        for (BaselineInstruction instruction : program.instructions()) {
            switch (instruction.opCode()) {
                case SET_CONST -> BaselineBytecodeOps.buildSetConst(visitor, instruction.primarySlot(), instruction.immediate());
                case ADD_CONST -> BaselineBytecodeOps.buildAddConst(visitor, instruction.primarySlot(), instruction.immediate());
                case GET -> BaselineBytecodeOps.buildGet(visitor, instruction.primarySlot());
                case RESET -> BaselineBytecodeOps.buildReset(visitor, instruction.primarySlot());
                case OPERATION -> BaselineBytecodeOps.buildOperation(visitor, instruction.primarySlot(), instruction.secondarySlot(), instruction.operation());
                case CALL -> BaselineBytecodeOps.buildCall(visitor, instruction.targetFunction().toString());
                case JUMP -> {
                    int localJumpIndex = unit.indexOf(instruction.targetFunction());
                    if (localJumpIndex >= 0) {
                        BaselineBytecodeOps.pushInt(visitor, localJumpIndex);
                        visitor.visitVarInsn(Opcodes.ISTORE, 4);
                        visitor.visitJumpInsn(Opcodes.GOTO, loopStart);
                    } else {
                        BaselineBytecodeOps.buildExternalJump(visitor, instruction.targetFunction().toString());
                    }
                }
                case RETURN_VALUE -> BaselineBytecodeOps.buildReturnValue(visitor, instruction.immediate());
            }
        }
        BaselineBytecodeOps.buildCompleted(visitor);
    }

    private static String generatedInternalName(net.minecraft.util.Identifier id) {
        String namespace = sanitize(id.getNamespace());
        String path = sanitize(id.getPath());
        return "asia/lira/mercury/jit/Generated_" + namespace + "_" + path + "_" + Integer.toHexString(id.hashCode());
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
}
