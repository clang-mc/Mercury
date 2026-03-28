package asia.lira.mercury.impl.cache;

import net.minecraft.util.Identifier;

public record MacroCallsiteKey(
        Identifier callerFunctionId,
        int nodeIndex,
        Identifier macroFunctionId,
        Identifier storageId,
        String storagePath
) {
}
