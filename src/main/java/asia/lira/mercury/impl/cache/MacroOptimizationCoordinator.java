package asia.lira.mercury.impl.cache;

import asia.lira.mercury.impl.FastMacro;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MacroOptimizationCoordinator {
    private static final MacroOptimizationCoordinator INSTANCE = new MacroOptimizationCoordinator();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mercury-macro-opt");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> queuedCandidates = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<PreparedMacroSpecialization> preparedQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Map<String, InstalledMacroSpecialization>> installedByPlanId = new ConcurrentHashMap<>();

    private MacroOptimizationCoordinator() {
    }

    public static MacroOptimizationCoordinator getInstance() {
        return INSTANCE;
    }

    public void clear() {
        queuedCandidates.clear();
        preparedQueue.clear();
        installedByPlanId.clear();
        RuntimeProfileRegistry.getInstance().clear();
    }

    public InstalledMacroSpecialization installed(int planId) {
        Map<String, InstalledMacroSpecialization> installed = installedByPlanId.get(planId);
        if (installed == null || installed.isEmpty()) {
            return null;
        }
        return installed.values().iterator().next();
    }

    public List<InstalledMacroSpecialization> installedVersions(int planId) {
        Map<String, InstalledMacroSpecialization> installed = installedByPlanId.get(planId);
        return installed == null ? List.of() : List.copyOf(installed.values());
    }

    public InstalledMacroSpecialization matchingInstalled(int planId, NbtCompound arguments) {
        for (InstalledMacroSpecialization specialization : installedVersions(planId)) {
            if (specialization.matches(arguments)) {
                return specialization;
            }
        }
        return null;
    }

    public void recordInvocation(
            int planId,
            MacroCallsiteKey callsiteKey,
            java.util.List<String> argumentNames,
            NbtCompound arguments,
        boolean prefetchHit,
        boolean specializedUsed,
        boolean guardHit
    ) {
        MacroCallsiteProfile profile = RuntimeProfileRegistry.getInstance().profileFor(planId, callsiteKey, argumentNames);
        profile.recordInvocation(arguments, prefetchHit, specializedUsed, guardHit);
        MacroPrefetchPlan plan = MacroPrefetchRegistry.getInstance().plan(planId);
        if (plan == null) {
            return;
        }
        int functionExecutions = RuntimeProfileRegistry.getInstance().functionExecutions(callsiteKey.callerFunctionId());
        int budget = specializationBudget(plan.macro().lines.size(), Math.max(functionExecutions, profile.totalCalls()));
        boolean requireDominantSingle = budget == 1;
        for (MacroSpecializationCandidate candidate : profile.specializationCandidates(budget, requireDominantSingle)) {
            String queueKey = planId + ":" + candidate.guardPlan().signature();
            if (!queuedCandidates.add(queueKey)) {
                continue;
            }
            worker.execute(() -> preparedQueue.add(new PreparedMacroSpecialization(
                    planId,
                    Identifier.of("mercury", "macro_specialized/" + planId + "_" + Integer.toHexString(queueKey.hashCode())),
                    candidate
            )));
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends AbstractServerCommandSource<T>> void installPending(CommandDispatcher<T> dispatcher) {
        PreparedMacroSpecialization prepared;
        while ((prepared = preparedQueue.poll()) != null) {
            MacroPrefetchPlan plan = MacroPrefetchRegistry.getInstance().plan(prepared.planId());
            if (plan == null) {
                continue;
            }
            Map<String, InstalledMacroSpecialization> installedForPlan =
                    installedByPlanId.computeIfAbsent(prepared.planId(), ignored -> new ConcurrentHashMap<>());
            if (installedForPlan.containsKey(prepared.candidate().guardPlan().signature())) {
                continue;
            }
            try {
                FastMacro<T> macro = (FastMacro<T>) plan.macro();
                FastMacro.MaterializedMacro<T> materialized = macro.materialize(prepared.candidate().guardPlan().expectedArguments(), dispatcher);
                BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().compileSynthetic(
                        prepared.syntheticId(),
                        materialized.sourceLines(),
                        materialized.actions()
                );
                Procedure<T> procedure;
                if (artifact != null) {
                    procedure = new asia.lira.mercury.jit.runtime.BaselineCompiledProcedure<T>(
                            prepared.syntheticId(),
                            java.util.List.of(new asia.lira.mercury.jit.runtime.BaselineCompiledAction<T>(artifact))
                    );
                } else {
                    procedure = materialized.procedure();
                }
                installedForPlan.put(prepared.candidate().guardPlan().signature(), new InstalledMacroSpecialization(
                        prepared.planId(),
                        prepared.candidate(),
                        procedure,
                        artifact
                ));
            } catch (Throwable ignored) {
            }
        }
    }

    public Map<Integer, InstalledMacroSpecialization> installedByPlanId() {
        Map<Integer, InstalledMacroSpecialization> flattened = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, InstalledMacroSpecialization>> entry : installedByPlanId.entrySet()) {
            entry.getValue().values().stream().findFirst().ifPresent(value -> flattened.put(entry.getKey(), value));
        }
        return Map.copyOf(flattened);
    }

    public Map<Integer, List<InstalledMacroSpecialization>> allInstalledVersions() {
        Map<Integer, List<InstalledMacroSpecialization>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, InstalledMacroSpecialization>> entry : installedByPlanId.entrySet()) {
            snapshot.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return Map.copyOf(snapshot);
    }

    public static InstalledMacroSpecialization requireInstalled(int planId, String guardSignature) {
        Map<String, InstalledMacroSpecialization> installed = INSTANCE.installedByPlanId.get(planId);
        if (installed == null) {
            throw new IllegalStateException("Missing installed macro specializations for plan " + planId);
        }
        InstalledMacroSpecialization specialization = installed.get(guardSignature);
        if (specialization == null) {
            throw new IllegalStateException("Missing installed macro specialization " + planId + ":" + guardSignature);
        }
        return specialization;
    }

    private static int specializationBudget(int macroLineCount, int functionExecutions) {
        int budget;
        if (macroLineCount <= 4) {
            budget = 4;
        } else if (macroLineCount <= 12) {
            budget = 2;
        } else {
            budget = 1;
        }
        if (functionExecutions >= 300) {
            budget = Math.min(4, budget + 1);
        }
        return budget;
    }
}
