package asia.lira.mercury.ir;

import net.minecraft.command.SourcedCommandAction;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FunctionActionCaptureRegistry {
    private static final FunctionActionCaptureRegistry INSTANCE = new FunctionActionCaptureRegistry();

    private final Map<Identifier, List<SourcedCommandAction<?>>> captures = new ConcurrentHashMap<>();
    private volatile Set<Identifier> expectedIds = Set.of();

    private FunctionActionCaptureRegistry() {
    }

    public static FunctionActionCaptureRegistry getInstance() {
        return INSTANCE;
    }

    public void beginReload(Collection<Identifier> ids) {
        expectedIds = Set.copyOf(ids);
        captures.clear();
    }

    public void capture(Identifier id, List<? extends SourcedCommandAction<?>> actions) {
        if (!expectedIds.contains(id)) {
            return;
        }
        captures.put(id, List.copyOf(actions));
    }

    public Map<Identifier, List<SourcedCommandAction<?>>> snapshot() {
        return new LinkedHashMap<>(captures);
    }

    public void clear() {
        expectedIds = Set.of();
        captures.clear();
    }
}
