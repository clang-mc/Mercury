package asia.lira.mercury.jit.pass;

import asia.lira.mercury.jit.pipeline.LoweredUnit;

public interface BaselinePass {
    LoweredUnit apply(LoweredUnit unit, BaselinePassContext context);
}
