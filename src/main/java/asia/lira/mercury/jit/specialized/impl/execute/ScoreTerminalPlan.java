package asia.lira.mercury.jit.specialized.impl.execute;

public record ScoreTerminalPlan(
        String sourceText,
        Operation operation,
        String targetHolder,
        String targetObjective,
        int value,
        String sourceHolder,
        String sourceObjective,
        String scoreboardOperation
) implements ExecuteTerminal {
    public enum Operation {
        SET,
        ADD,
        GET,
        RESET,
        SCORE_OPERATION
    }
}
