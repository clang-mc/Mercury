package asia.lira.mercury.jit;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ObjectiveRegistry {
    private final Map<String, Integer> ids = new LinkedHashMap<>();
    private final List<String> names = new ArrayList<>();

    public void clear() {
        ids.clear();
        names.clear();
    }

    public int register(String objectiveName) {
        Integer existing = ids.get(objectiveName);
        if (existing != null) {
            return existing;
        }

        int id = names.size();
        ids.put(objectiveName, id);
        names.add(objectiveName);
        return id;
    }

    public @Nullable Integer getId(String objectiveName) {
        return ids.get(objectiveName);
    }

    public @Nullable String getName(int id) {
        if (id < 0 || id >= names.size()) {
            return null;
        }
        return names.get(id);
    }

    public int count() {
        return names.size();
    }
}
