package asia.lira.mercury.impl.cache;

import net.minecraft.util.Identifier;

import java.util.List;

public record MacroPrefetchKey(
        Identifier storageId,
        String storagePath,
        Identifier macroFunctionId,
        List<String> argumentShape
) {
    public MacroPrefetchKey {
        argumentShape = List.copyOf(argumentShape);
    }
}
