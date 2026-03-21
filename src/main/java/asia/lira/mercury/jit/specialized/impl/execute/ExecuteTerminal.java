package asia.lira.mercury.jit.specialized.impl.execute;

public sealed interface ExecuteTerminal permits ScoreTerminalPlan, DataStorageTerminalPlan {
    String sourceText();
}
