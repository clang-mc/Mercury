package asia.lira.mercury.jit.pipeline;

public enum CommandLoweringKind {
    CONTROL_FLOW,
    SCOREBOARD_GET,
    SCOREBOARD_SET,
    SCOREBOARD_ADD,
    SCOREBOARD_REMOVE,
    SCOREBOARD_RESET,
    SCOREBOARD_OPERATION,
    BRIDGE,
    FALLBACK
}
