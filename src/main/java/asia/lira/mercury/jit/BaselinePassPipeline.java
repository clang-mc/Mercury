package asia.lira.mercury.jit;

import java.util.List;

public final class BaselinePassPipeline {
    private final List<BaselinePass> passes;

    public BaselinePassPipeline(List<BaselinePass> passes) {
        this.passes = List.copyOf(passes);
    }

    public LoweredUnit apply(LoweredUnit unit, BaselinePassContext context) {
        LoweredUnit current = unit;
        for (BaselinePass pass : passes) {
            current = pass.apply(current, context);
        }
        return current;
    }
}
