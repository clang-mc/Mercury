package asia.lira.mercury.impl.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MacroArgumentValueProfile {
    private static final int MAX_VALUES = 4;

    private final String argumentName;
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public MacroArgumentValueProfile(String argumentName) {
        this.argumentName = argumentName;
    }

    public void record(String value) {
        counts.merge(value, 1, Integer::sum);
        if (counts.size() <= MAX_VALUES) {
            return;
        }
        String smallestKey = null;
        int smallest = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < smallest) {
                smallest = entry.getValue();
                smallestKey = entry.getKey();
            }
        }
        if (smallestKey != null) {
            counts.remove(smallestKey);
        }
    }

    public String argumentName() {
        return argumentName;
    }

    public Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }
}
