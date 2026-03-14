package asia.lira.mercury.jit;

@FunctionalInterface
public interface CompiledFunction {
    int invoke(ExecutionFrame frame) throws Throwable;
}
