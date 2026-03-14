package asia.lira.mercury.jit;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;

public final class BaselineBytecodeCompiler {
    private static final String COMPILED_FUNCTION_INTERNAL = Type.getInternalName(CompiledFunction.class);
    private static final String PROGRAM_RUNTIME_INTERNAL = Type.getInternalName(BaselineExecutionEngine.class);
    private static final String EXECUTION_FRAME_DESC = Type.getDescriptor(ExecutionFrame.class);
    private static final String COMMAND_EXECUTION_CONTEXT_DESC = Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class);
    private static final String EXECUTION_OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);

    private BaselineBytecodeCompiler() {
    }

    public static BaselineCompiledFunctionRegistry.CompiledArtifact compile(BaselineProgram program) {
        String internalName = generatedInternalName(program.id());
        byte[] classBytes = generateClass(program, internalName);

        try {
            MethodHandles.Lookup hiddenLookup = MethodHandles.lookup().defineHiddenClass(classBytes, true);
            Class<?> hiddenClass = hiddenLookup.lookupClass();
            CompiledFunction compiledFunction = (CompiledFunction) hiddenClass.getDeclaredConstructor().newInstance();
            return new BaselineCompiledFunctionRegistry.CompiledArtifact(program, compiledFunction, classBytes, internalName);
        } catch (Throwable throwable) {
            throw new RuntimeException("Failed to define compiled class for " + program.id(), throwable);
        }
    }

    private static byte[] generateClass(BaselineProgram program, String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", new String[]{COMPILED_FUNCTION_INTERNAL});

        emitConstructor(writer);
        emitInvoke(writer, program);

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

    /**
     * This method is the single low-level ASM lowering boundary.
     * Each baseline instruction is emitted as one explicit helper call so the
     * generated .class stays easy to dump and inspect during bring-up.
     */
    private static void emitInvoke(ClassWriter writer, BaselineProgram program) {
        String descriptor = "(" + EXECUTION_FRAME_DESC + "Ljava/lang/Object;" + COMMAND_EXECUTION_CONTEXT_DESC + ")" + EXECUTION_OUTCOME_DESC;
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke", descriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();

        for (BaselineInstruction instruction : program.instructions()) {
            switch (instruction.opCode()) {
                case SET_CONST -> {
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    pushInt(visitor, instruction.primarySlot());
                    pushInt(visitor, instruction.immediate());
                    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "opSetConst", "(" + EXECUTION_FRAME_DESC + "II)V", false);
                }
                case ADD_CONST -> {
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    pushInt(visitor, instruction.primarySlot());
                    pushInt(visitor, instruction.immediate());
                    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "opAddConst", "(" + EXECUTION_FRAME_DESC + "II)V", false);
                }
                case GET -> {
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    pushInt(visitor, instruction.primarySlot());
                    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "opGet", "(" + EXECUTION_FRAME_DESC + "I)V", false);
                }
                case RESET -> {
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    pushInt(visitor, instruction.primarySlot());
                    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "opReset", "(" + EXECUTION_FRAME_DESC + "I)V", false);
                }
                case OPERATION -> {
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    pushInt(visitor, instruction.primarySlot());
                    pushInt(visitor, instruction.secondarySlot());
                    visitor.visitLdcInsn(instruction.operation());
                    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "opOperation", "(" + EXECUTION_FRAME_DESC + "IILjava/lang/String;)V", false);
                }
                case CALL -> {
                    visitor.visitLdcInsn(instruction.targetFunction().toString());
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    visitor.visitVarInsn(Opcodes.ALOAD, 2);
                    visitor.visitVarInsn(Opcodes.ALOAD, 3);
                    visitor.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            PROGRAM_RUNTIME_INTERNAL,
                            "opCall",
                            "(Ljava/lang/String;" + EXECUTION_FRAME_DESC + "Ljava/lang/Object;" + COMMAND_EXECUTION_CONTEXT_DESC + ")V",
                            false
                    );
                }
                case JUMP -> {
                    visitor.visitLdcInsn(instruction.targetFunction().toString());
                    visitor.visitVarInsn(Opcodes.ALOAD, 1);
                    visitor.visitVarInsn(Opcodes.ALOAD, 2);
                    visitor.visitVarInsn(Opcodes.ALOAD, 3);
                    visitor.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            PROGRAM_RUNTIME_INTERNAL,
                            "opJump",
                            "(Ljava/lang/String;" + EXECUTION_FRAME_DESC + "Ljava/lang/Object;" + COMMAND_EXECUTION_CONTEXT_DESC + ")" + EXECUTION_OUTCOME_DESC,
                            false
                    );
                    visitor.visitInsn(Opcodes.ARETURN);
                }
                case RETURN_VALUE -> {
                    pushInt(visitor, instruction.immediate());
                    visitor.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            PROGRAM_RUNTIME_INTERNAL,
                            "returnValue",
                            "(I)" + EXECUTION_OUTCOME_DESC,
                            false
                    );
                    visitor.visitInsn(Opcodes.ARETURN);
                }
            }
        }

        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PROGRAM_RUNTIME_INTERNAL, "completed", "()" + EXECUTION_OUTCOME_DESC, false);
        visitor.visitInsn(Opcodes.ARETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void pushInt(MethodVisitor visitor, int value) {
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
