package asia.lira.mercury.jit.specialized.impl.execute;

import asia.lira.mercury.jit.specialized.api.SpecializedPlan;

import java.util.List;

public record ExecutePlan(
        String sourceText,
        List<ExecuteModifier> modifiers,
        ExecuteTerminal terminal
) implements SpecializedPlan {
}
