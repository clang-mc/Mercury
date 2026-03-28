package asia.lira.mercury.impl.cache;

public final class MacroPrefetchStats {
    private long hits;
    private long misses;
    private long promotions;
    private long evictions;
    private long fallbacks;

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long promotions() {
        return promotions;
    }

    public long evictions() {
        return evictions;
    }

    public long fallbacks() {
        return fallbacks;
    }

    public void recordHit() {
        hits++;
    }

    public void recordMiss() {
        misses++;
    }

    public void recordPromotion() {
        promotions++;
    }

    public void recordEviction() {
        evictions++;
    }

    public void recordFallback() {
        fallbacks++;
    }

    public void reset() {
        hits = 0;
        misses = 0;
        promotions = 0;
        evictions = 0;
        fallbacks = 0;
    }
}
