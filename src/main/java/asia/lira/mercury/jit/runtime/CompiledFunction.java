package asia.lira.mercury.jit.runtime;

import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;

@FunctionalInterface
public interface CompiledFunction {
    BaselineExecutionEngine.ExecutionOutcome invoke(ExecutionFrame frame, Object source, CommandExecutionContext<?> context, Frame commandFrame, int initialState) throws Throwable;
}
