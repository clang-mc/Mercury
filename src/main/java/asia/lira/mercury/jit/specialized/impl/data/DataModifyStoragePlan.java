package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.jit.specialized.api.SpecializedEmitContext;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.Identifier;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public record DataModifyStoragePlan(
        String sourceText,
        Operation operation,
        Identifier targetStorageId,
        NbtPathArgumentType.NbtPath targetPath,
        NbtElement value,
        Identifier sourceStorageId,
        NbtPathArgumentType.NbtPath sourcePath
) implements SpecializedPlan {
    @Override
    public void emitBytecode(SpecializedEmitContext context) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataModifyStoragePlan.class),
                "targetStorageId",
                "()" + Type.getDescriptor(Identifier.class),
                false
        );
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataModifyStoragePlan.class),
                "targetPath",
                "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                false
        );

        switch (operation) {
            case SET_VALUE -> {
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "value",
                        "()" + Type.getDescriptor(NbtElement.class),
                        false
                );
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "setValue",
                        "(" + Type.getDescriptor(Identifier.class) + Type.getDescriptor(NbtPathArgumentType.NbtPath.class) + Type.getDescriptor(NbtElement.class) + ")I",
                        false
                );
            }
            case SET_FROM_STORAGE -> {
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "sourceStorageId",
                        "()" + Type.getDescriptor(Identifier.class),
                        false
                );
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "sourcePath",
                        "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                        false
                );
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "setFromStorage",
                        "(" + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + ")I",
                        false
                );
            }
            case MERGE_VALUE -> {
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "value",
                        "()" + Type.getDescriptor(NbtElement.class),
                        false
                );
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "mergeValue",
                        "(" + Type.getDescriptor(Identifier.class) + Type.getDescriptor(NbtPathArgumentType.NbtPath.class) + Type.getDescriptor(NbtElement.class) + ")I",
                        false
                );
            }
            case MERGE_FROM_STORAGE -> {
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "sourceStorageId",
                        "()" + Type.getDescriptor(Identifier.class),
                        false
                );
                context.loadPlan();
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        Type.getInternalName(DataModifyStoragePlan.class),
                        "sourcePath",
                        "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                        false
                );
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "mergeFromStorage",
                        "(" + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + ")I",
                        false
                );
            }
        }
        context.visitor().visitInsn(Opcodes.POP);
    }

    public enum Operation {
        SET_VALUE,
        SET_FROM_STORAGE,
        MERGE_VALUE,
        MERGE_FROM_STORAGE
    }
}
