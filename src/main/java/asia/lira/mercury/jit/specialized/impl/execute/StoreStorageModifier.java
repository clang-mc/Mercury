package asia.lira.mercury.jit.specialized.impl.execute;

import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.util.Identifier;

public record StoreStorageModifier(
        boolean requestResult,
        Identifier storageId,
        NbtPathArgumentType.NbtPath path,
        String numericType,
        double scale
) implements ExecuteModifier {
}
