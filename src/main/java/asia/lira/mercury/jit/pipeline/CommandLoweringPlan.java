package asia.lira.mercury.jit.pipeline;

import java.util.List;

public record CommandLoweringPlan(
        CommandLoweringKind kind,
        List<Integer> slotIds,
        List<String> notes
) {
    public static CommandLoweringPlan of(CommandLoweringKind kind, List<Integer> slotIds, List<String> notes) {
        return new CommandLoweringPlan(kind, List.copyOf(slotIds), List.copyOf(notes));
    }
}
