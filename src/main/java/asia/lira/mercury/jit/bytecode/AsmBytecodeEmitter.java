package asia.lira.mercury.jit.bytecode;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class AsmBytecodeEmitter implements BytecodeEmitter {
    @Override
    public GeneratedClass beginClass(String internalName, String superInternalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, superInternalName, null);
        return new GeneratedClassImpl(writer);
    }

    private static final class GeneratedClassImpl implements GeneratedClass {
        private final ClassWriter writer;

        private GeneratedClassImpl(ClassWriter writer) {
            this.writer = writer;
        }

        @Override
        public GeneratedMethod beginMethod(int access, String name, Type returnType, Type... argumentTypes) {
            String descriptor = Type.getMethodDescriptor(returnType, argumentTypes);
            MethodVisitor visitor = writer.visitMethod(access, name, descriptor, null, null);
            visitor.visitCode();
            return new GeneratedMethodImpl(visitor);
        }

        @Override
        public byte[] toByteArray() {
            writer.visitEnd();
            return writer.toByteArray();
        }
    }

    private static final class GeneratedMethodImpl implements GeneratedMethod {
        private final MethodVisitor visitor;

        private GeneratedMethodImpl(MethodVisitor visitor) {
            this.visitor = visitor;
        }

        @Override
        public void loadThis() {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
        }

        @Override
        public void loadArgument(int index) {
            visitor.visitVarInsn(Opcodes.ALOAD, index + 1);
        }

        @Override
        public void loadInt(int value) {
            visitor.visitLdcInsn(value);
        }

        @Override
        public void getField(String ownerInternalName, String name, Type type) {
            visitor.visitFieldInsn(Opcodes.GETFIELD, ownerInternalName, name, type.getDescriptor());
        }

        @Override
        public void putField(String ownerInternalName, String name, Type type) {
            visitor.visitFieldInsn(Opcodes.PUTFIELD, ownerInternalName, name, type.getDescriptor());
        }

        @Override
        public void invokeStatic(String ownerInternalName, String name, Type returnType, Type... argumentTypes) {
            visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    ownerInternalName,
                    name,
                    Type.getMethodDescriptor(returnType, argumentTypes),
                    false
            );
        }

        @Override
        public void invokeVirtual(String ownerInternalName, String name, Type returnType, Type... argumentTypes) {
            visitor.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    ownerInternalName,
                    name,
                    Type.getMethodDescriptor(returnType, argumentTypes),
                    false
            );
        }

        @Override
        public void returnInt() {
            visitor.visitInsn(Opcodes.IRETURN);
        }

        @Override
        public void returnVoid() {
            visitor.visitInsn(Opcodes.RETURN);
        }

        @Override
        public void endMethod() {
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }
    }
}
