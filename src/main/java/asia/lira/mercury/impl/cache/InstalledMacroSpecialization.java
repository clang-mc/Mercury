package asia.lira.mercury.impl.cache;

import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import net.minecraft.server.function.Procedure;

public record InstalledMacroSpecialization(
        int planId,
        MacroSpecializationCandidate candidate,
        Procedure<?> procedure,
        BaselineCompiledFunctionRegistry.CompiledArtifact artifact
) {
    public String guardSignature() {
        return candidate.guardPlan().signature();
    }

    public boolean matches(net.minecraft.nbt.NbtCompound arguments) {
        return candidate.guardPlan().matches(arguments);
    }
}
