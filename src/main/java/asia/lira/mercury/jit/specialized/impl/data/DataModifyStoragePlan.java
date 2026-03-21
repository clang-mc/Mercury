package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.util.Identifier;

public record DataModifyStoragePlan(
        String sourceText,
        Operation operation,
        Identifier targetStorageId,
        NbtPathArgumentType.NbtPath targetPath,
        NbtElement value,
        Identifier sourceStorageId,
        NbtPathArgumentType.NbtPath sourcePath
) implements SpecializedPlan {
    public enum Operation {
        SET_VALUE,
        SET_FROM_STORAGE,
        MERGE_VALUE,
        MERGE_FROM_STORAGE
    }
}
