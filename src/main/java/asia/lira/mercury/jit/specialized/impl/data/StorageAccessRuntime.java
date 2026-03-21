package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.Mercury;
import com.google.common.collect.Iterables;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;

public final class StorageAccessRuntime {
    private static final SimpleCommandExceptionType MERGE_FAILED_EXCEPTION = new SimpleCommandExceptionType(Text.translatable("commands.data.merge.failed"));
    private static final DynamicCommandExceptionType MODIFY_EXPECTED_OBJECT_EXCEPTION = new DynamicCommandExceptionType(
            nbt -> Text.stringifiedTranslatable("commands.data.modify.expected_object", nbt)
    );

    private StorageAccessRuntime() {
    }

    public static int setValue(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, NbtElement value) throws CommandSyntaxException {
        NbtCompound root = getMutable(storageId);
        int changed = targetPath.put(root, value.copy());
        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(storageId, root);
        return changed;
    }

    public static int setFromStorage(
            Identifier targetStorageId,
            NbtPathArgumentType.NbtPath targetPath,
            Identifier sourceStorageId,
            NbtPathArgumentType.NbtPath sourcePath
    ) throws CommandSyntaxException {
        Collection<NbtElement> values = sourcePath.get(Mercury.SERVER.getDataCommandStorage().get(sourceStorageId));
        if (values.isEmpty()) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        NbtCompound root = getMutable(targetStorageId);
        int changed = targetPath.put(root, Iterables.getLast(values).copy());
        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(targetStorageId, root);
        return changed;
    }

    public static int mergeValue(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, NbtElement value) throws CommandSyntaxException {
        if (!(value instanceof NbtCompound compound)) {
            throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(value);
        }
        return mergeElements(storageId, targetPath, List.of(compound));
    }

    public static int mergeFromStorage(
            Identifier targetStorageId,
            NbtPathArgumentType.NbtPath targetPath,
            Identifier sourceStorageId,
            NbtPathArgumentType.NbtPath sourcePath
    ) throws CommandSyntaxException {
        Collection<NbtElement> values = sourcePath.get(Mercury.SERVER.getDataCommandStorage().get(sourceStorageId));
        return mergeElements(targetStorageId, targetPath, values);
    }

    private static int mergeElements(Identifier storageId, NbtPathArgumentType.NbtPath targetPath, Collection<NbtElement> elements) throws CommandSyntaxException {
        NbtCompound merged = new NbtCompound();
        for (NbtElement element : elements) {
            if (!(element instanceof NbtCompound compound)) {
                throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(element);
            }
            merged.copyFrom(compound);
        }

        NbtCompound root = getMutable(storageId);
        Collection<NbtElement> targets = targetPath.getOrInit(root, NbtCompound::new);
        int changed = 0;
        for (NbtElement target : targets) {
            if (!(target instanceof NbtCompound compoundTarget)) {
                throw MODIFY_EXPECTED_OBJECT_EXCEPTION.create(target);
            }
            NbtCompound copy = compoundTarget.copy();
            compoundTarget.copyFrom(merged);
            changed += copy.equals(compoundTarget) ? 0 : 1;
        }

        if (changed == 0) {
            throw MERGE_FAILED_EXCEPTION.create();
        }
        save(storageId, root);
        return changed;
    }

    private static NbtCompound getMutable(Identifier storageId) {
        DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
        return storage.get(storageId).copy();
    }

    private static void save(Identifier storageId, NbtCompound root) {
        Mercury.SERVER.getDataCommandStorage().set(storageId, root);
    }
}
