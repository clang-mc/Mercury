package asia.lira.mercury.impl.cache;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.function.MacroException;

public interface MacroArgumentProvider {
    NbtElement resolveArgument(String name, int index) throws MacroException;

    default NbtCompound resolveArguments(Iterable<String> names) throws MacroException {
        NbtCompound compound = new NbtCompound();
        int index = 0;
        for (String name : names) {
            NbtElement value = resolveArgument(name, index++);
            compound.put(name, value.copy());
        }
        return compound;
    }
}
