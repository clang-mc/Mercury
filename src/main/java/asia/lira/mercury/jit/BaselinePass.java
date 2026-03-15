package asia.lira.mercury.jit;

public interface BaselinePass {
    LoweredUnit apply(LoweredUnit unit, BaselinePassContext context);
}
