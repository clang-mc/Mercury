package asia.lira.mercury.impl.cache;

import net.minecraft.nbt.NbtCompound;

import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public final class MacroCallsiteProfile {
    private final MacroCallsiteKey key;
    private final Map<String, MacroArgumentValueProfile> fieldProfiles = new LinkedHashMap<>();
    private final Map<String, GuardCount> exactGuards = new LinkedHashMap<>();
    private int totalCalls;
    private int prefetchHits;
    private int prefetchMisses;
    private int guardHits;
    private int guardMisses;
    private int specializationUses;
    private int fallbackUses;

    public MacroCallsiteProfile(MacroCallsiteKey key, List<String> argumentNames) {
        this.key = key;
        for (String argumentName : argumentNames) {
            fieldProfiles.put(argumentName, new MacroArgumentValueProfile(argumentName));
        }
    }

    public void recordInvocation(NbtCompound arguments, boolean prefetchHit, boolean specializedUsed, boolean guardHit) {
        totalCalls++;
        if (prefetchHit) {
            prefetchHits++;
        } else {
            prefetchMisses++;
        }
        if (specializedUsed) {
            specializationUses++;
        } else {
            fallbackUses++;
        }
        if (guardHit) {
            guardHits++;
        } else {
            guardMisses++;
        }

        for (Map.Entry<String, MacroArgumentValueProfile> entry : fieldProfiles.entrySet()) {
            entry.getValue().record(String.valueOf(arguments.get(entry.getKey())));
        }

        MacroGuardPlan guardPlan = new MacroGuardPlan(List.copyOf(fieldProfiles.keySet()), arguments);
        exactGuards.computeIfAbsent(guardPlan.signature(), ignored -> new GuardCount(guardPlan))
                .increment();
    }

    public MacroSpecializationCandidate bestCandidate() {
        List<MacroSpecializationCandidate> candidates = specializationCandidates(1, false);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    public List<MacroSpecializationCandidate> specializationCandidates(int budget, boolean requireDominantSingle) {
        if (totalCalls < 4) {
            return List.of();
        }

        List<GuardCount> sorted = new ArrayList<>(exactGuards.values());
        sorted.sort(Comparator.comparingInt((GuardCount guard) -> guard.count).reversed());

        List<MacroSpecializationCandidate> result = new ArrayList<>();
        for (GuardCount guardCount : sorted) {
            if (result.size() >= budget) {
                break;
            }
            if (guardCount.count < 3) {
                continue;
            }
            double ratio = (double) guardCount.count / totalCalls;
            if (requireDominantSingle && ratio < 0.50d) {
                continue;
            }
            result.add(new MacroSpecializationCandidate(key, guardCount.guardPlan, totalCalls, guardCount.count, ratio));
        }
        return List.copyOf(result);
    }

    public MacroCallsiteKey key() {
        return key;
    }

    public int totalCalls() {
        return totalCalls;
    }

    public int prefetchHits() {
        return prefetchHits;
    }

    public int prefetchMisses() {
        return prefetchMisses;
    }

    public int guardHits() {
        return guardHits;
    }

    public int guardMisses() {
        return guardMisses;
    }

    public int specializationUses() {
        return specializationUses;
    }

    public int fallbackUses() {
        return fallbackUses;
    }

    public Map<String, MacroArgumentValueProfile> fieldProfiles() {
        return Map.copyOf(fieldProfiles);
    }

    private static final class GuardCount {
        private final MacroGuardPlan guardPlan;
        private int count;

        private GuardCount(MacroGuardPlan guardPlan) {
            this.guardPlan = guardPlan;
        }

        private void increment() {
            count++;
        }
    }
}
