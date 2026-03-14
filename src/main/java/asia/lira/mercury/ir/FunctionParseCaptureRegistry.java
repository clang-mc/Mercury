package asia.lira.mercury.ir;

import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FunctionParseCaptureRegistry {
    private static final FunctionParseCaptureRegistry INSTANCE = new FunctionParseCaptureRegistry();

    private final Map<Identifier, FunctionIrRegistry.ParsedFunctionIr> captures = new ConcurrentHashMap<>();
    private volatile Set<Identifier> expectedIds = Set.of();

    private FunctionParseCaptureRegistry() {
    }

    public static FunctionParseCaptureRegistry getInstance() {
        return INSTANCE;
    }

    public void beginReload(Collection<Identifier> ids) {
        expectedIds = Set.copyOf(ids);
        captures.clear();
    }

    public void capture(FunctionIrRegistry.ParsedFunctionIr functionIr) {
        if (!expectedIds.contains(functionIr.id())) {
            return;
        }
        captures.put(functionIr.id(), functionIr);
    }

    public Map<Identifier, FunctionIrRegistry.ParsedFunctionIr> snapshot() {
        return new LinkedHashMap<>(captures);
    }

    public void clear() {
        expectedIds = Set.of();
        captures.clear();
    }
}
