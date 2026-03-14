package asia.lira.mercury.jit;

import net.minecraft.command.CommandExecutionContext;

@FunctionalInterface
public interface CompiledFunction {
    BaselineExecutionEngine.ExecutionOutcome invoke(ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable;
}
