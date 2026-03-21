package asia.lira.mercury.jit.specialized.api;

import org.jetbrains.annotations.Nullable;

public interface SpecializationAnalyzer {
    @Nullable SpecializedPlan analyze(String sourceText);
}
