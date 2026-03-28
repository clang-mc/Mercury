package asia.lira.mercury.jit.registry;

import asia.lira.mercury.impl.cache.InstalledMacroSpecialization;
import asia.lira.mercury.impl.cache.MacroOptimizationCoordinator;
import asia.lira.mercury.jit.pipeline.LoweredUnit;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Tier2CompilationCoordinator {
    private static final Tier2CompilationCoordinator INSTANCE = new Tier2CompilationCoordinator();
    private static final int TIER2_THRESHOLD = 100;

    private final Set<Identifier> installedTier2 = new LinkedHashSet<>();
    private final Map<Identifier, Integer> retryThresholds = new LinkedHashMap<>();

    private Tier2CompilationCoordinator() {
    }

    public static Tier2CompilationCoordinator getInstance() {
        return INSTANCE;
    }

    public void clear() {
        installedTier2.clear();
        retryThresholds.clear();
    }

    public void onFunctionInvocation(Identifier functionId, com.mojang.brigadier.CommandDispatcher<?> dispatcher) {
        int executions = asia.lira.mercury.impl.cache.RuntimeProfileRegistry.getInstance().incrementFunctionExecution(functionId);
        if (installedTier2.contains(functionId)) {
            return;
        }
        int threshold = retryThresholds.getOrDefault(functionId, TIER2_THRESHOLD);
        if (executions < threshold) {
            return;
        }

        installPendingUnchecked(dispatcher);
        BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().getTier1Artifact(functionId);
        if (artifact == null || artifact.kind() != BaselineCompiledFunctionRegistry.ArtifactKind.TIER1) {
            retryThresholds.put(functionId, executions + 25);
            return;
        }

        LoweredUnit rewritten = rewriteTier2(functionId, artifact.optimizedUnit());
        if (rewritten == null) {
            retryThresholds.put(functionId, executions + 25);
            return;
        }

        BaselineCompiledFunctionRegistry.CompiledArtifact tier2Artifact = BaselineCompiledFunctionRegistry.getInstance().compileTier2(functionId, rewritten);
        if (tier2Artifact == null) {
            retryThresholds.put(functionId, executions + 25);
            return;
        }

        BaselineCompiledFunctionRegistry.getInstance().installTier2Artifact(functionId, tier2Artifact);
        installedTier2.add(functionId);
        retryThresholds.remove(functionId);
    }

    @SuppressWarnings("unchecked")
    private static void installPendingUnchecked(com.mojang.brigadier.CommandDispatcher<?> dispatcher) {
        MacroOptimizationCoordinator.getInstance().installPending((com.mojang.brigadier.CommandDispatcher) dispatcher);
    }

    public boolean isTier2Installed(Identifier functionId) {
        return installedTier2.contains(functionId);
    }

    private static LoweredUnit rewriteTier2(Identifier functionId, LoweredUnit unit) {
        boolean changed = false;
        List<LoweredUnit.LoweredBlock> rewrittenBlocks = new ArrayList<>(unit.blocks().size());
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            List<LoweredUnit.LoweredInstruction> rewrittenInstructions = new ArrayList<>(block.instructions().size());
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                if (instruction instanceof LoweredUnit.PrefetchedMacroCallInstruction prefetchedMacroCallInstruction) {
                    List<LoweredUnit.Tier2DispatchTarget> targets = compiledTargets(prefetchedMacroCallInstruction.planId());
                    if (!targets.isEmpty()) {
                        rewrittenInstructions.add(new LoweredUnit.Tier2MacroDispatchInstruction(
                                prefetchedMacroCallInstruction.planId(),
                                prefetchedMacroCallInstruction.spillBeforeSlots(),
                                prefetchedMacroCallInstruction.reloadAfterSlots(),
                                targets,
                                prefetchedMacroCallInstruction.sourceText()
                        ));
                        changed = true;
                        continue;
                    }
                }
                rewrittenInstructions.add(instruction);
            }

            LoweredUnit.LoweredTerminator terminator = block.terminator();
            if (terminator instanceof LoweredUnit.SuspendPrefetchedMacroTerminator suspendPrefetchedMacroTerminator) {
                List<LoweredUnit.Tier2DispatchTarget> targets = compiledTargets(suspendPrefetchedMacroTerminator.planId());
                if (!targets.isEmpty()) {
                    terminator = new LoweredUnit.Tier2MacroDispatchTerminator(
                            suspendPrefetchedMacroTerminator.planId(),
                            suspendPrefetchedMacroTerminator.continuationBlockIndex(),
                            suspendPrefetchedMacroTerminator.spillBeforeSlots(),
                            targets
                    );
                    changed = true;
                }
            }

            rewrittenBlocks.add(new LoweredUnit.LoweredBlock(block.programId(), rewrittenInstructions, terminator));
        }
        if (!changed) {
            return null;
        }
        return new LoweredUnit(functionId, rewrittenBlocks, unit.entryIndex(), unit.requiredSlots(), unit.promotedSlotLocals());
    }

    private static List<LoweredUnit.Tier2DispatchTarget> compiledTargets(int planId) {
        List<LoweredUnit.Tier2DispatchTarget> targets = new ArrayList<>();
        for (InstalledMacroSpecialization installed : MacroOptimizationCoordinator.getInstance().installedVersions(planId)) {
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact = installed.artifact();
            if (artifact == null || artifact.kind() != BaselineCompiledFunctionRegistry.ArtifactKind.SYNTHETIC) {
                continue;
            }
            targets.add(new LoweredUnit.Tier2DispatchTarget(
                    installed.guardSignature(),
                    artifact.program().id(),
                    artifact.internalClassName(),
                    artifact.requiredSlots()
            ));
        }
        return List.copyOf(targets);
    }
}
