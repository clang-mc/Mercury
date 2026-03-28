package asia.lira.mercury.impl.cache;

import net.minecraft.util.Identifier;

public final class FunctionExecutionProfile {
    private final Identifier functionId;
    private int executions;

    public FunctionExecutionProfile(Identifier functionId) {
        this.functionId = functionId;
    }

    public int incrementAndGet() {
        executions++;
        return executions;
    }

    public Identifier functionId() {
        return functionId;
    }

    public int executions() {
        return executions;
    }
}
