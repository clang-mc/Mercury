package asia.lira.mercury.jit.specialized.api;

public interface SpecializedPlan {
    String sourceText();

    void emitBytecode(SpecializedEmitContext context);
}
