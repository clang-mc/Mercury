package asia.lira.mercury.jit.specialized.api;

import asia.lira.mercury.jit.BaselineBytecodeOps;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class SpecializedEmitContext {
    private final MethodVisitor visitor;
    private final String ownerInternalName;
    private final String planFieldName;
    private final String planDescriptor;

    public SpecializedEmitContext(
            MethodVisitor visitor,
            String ownerInternalName,
            String planFieldName,
            String planDescriptor
    ) {
        this.visitor = visitor;
        this.ownerInternalName = ownerInternalName;
        this.planFieldName = planFieldName;
        this.planDescriptor = planDescriptor;
    }

    public MethodVisitor visitor() {
        return visitor;
    }

    public void pushInt(int value) {
        BaselineBytecodeOps.pushInt(visitor, value);
    }

    public void loadFrame() {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
    }

    public void loadSourceAsServerCommandSource() {
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(net.minecraft.server.command.ServerCommandSource.class));
    }

    public void loadPlan() {
        visitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, planFieldName, planDescriptor);
    }
}
