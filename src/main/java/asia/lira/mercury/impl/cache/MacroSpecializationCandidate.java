package asia.lira.mercury.impl.cache;

public record MacroSpecializationCandidate(
        MacroCallsiteKey callsiteKey,
        MacroGuardPlan guardPlan,
        int totalCalls,
        int matchingCalls,
        double dominanceRatio
) {
}
