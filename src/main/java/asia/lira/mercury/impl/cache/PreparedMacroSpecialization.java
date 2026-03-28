package asia.lira.mercury.impl.cache;

import net.minecraft.util.Identifier;

public record PreparedMacroSpecialization(
        int planId,
        Identifier syntheticId,
        MacroSpecializationCandidate candidate
) {
}
