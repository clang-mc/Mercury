package asia.lira.mercury.jit.specialized.impl.execute;

import net.minecraft.predicate.NumberRange;

public record IfScoreMatchesModifier(
        String targetHolder,
        String targetObjective,
        NumberRange.IntRange range
) implements ExecuteModifier {
}
