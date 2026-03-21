package asia.lira.mercury.jit.specialized.api;

import asia.lira.mercury.jit.ExecutionFrame;
import net.minecraft.server.command.ServerCommandSource;

public interface SpecializedExecutor<T extends SpecializedPlan> {
    void execute(T plan, ExecutionFrame frame, ServerCommandSource source) throws Exception;
}
