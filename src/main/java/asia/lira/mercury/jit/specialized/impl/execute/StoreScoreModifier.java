package asia.lira.mercury.jit.specialized.impl.execute;

public record StoreScoreModifier(
        boolean requestResult,
        String holder,
        String objective
) implements ExecuteModifier {
}
