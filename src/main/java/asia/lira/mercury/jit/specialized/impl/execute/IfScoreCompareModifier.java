package asia.lira.mercury.jit.specialized.impl.execute;

public record IfScoreCompareModifier(
        String targetHolder,
        String targetObjective,
        String operator,
        String sourceHolder,
        String sourceObjective
) implements ExecuteModifier {
}
