package dev.nativecomplements.navdemo;

/** One in-memory review, with an absolute TTL (hits never renew it). No image persistence. */
final class DemoReviewCache<T> {
    private Object key;
    private long generation, storedAt, ttl;
    private int[] strip;
    private T value;
    private long nextAttempt;

    /** Global 1 Hz large-ROI cap; cache misses never bypass it, hits do not renew review TTL. */
    boolean begin(long now) {
        if (now < nextAttempt) return false;
        nextAttempt = now + 1000;
        return true;
    }

    T get(Object key, long generation, int[] strip, long now) {
        return this.key != null && this.key.equals(key) && this.generation == generation
                && now >= storedAt && now - storedAt <= ttl && similar(this.strip, strip) ? value : null;
    }

    void put(Object key, long generation, int[] strip, T value, long now, boolean failed) {
        this.key = key;
        this.generation = generation;
        this.strip = strip.clone();
        this.value = value;
        storedAt = now;
        ttl = failed ? 1000 : 3000;
    }

    long age(long now) { return now - storedAt; }
    void clear() { key = null; strip = null; value = null; }

    /** RGB tolerance 8 per channel, at most floor(N/100) mismatches; alpha must stay opaque. */
    static boolean similar(int[] first, int[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        int different = 0;
        for (int i = 0; i < first.length; i++) {
            if ((first[i] >>> 24) != 255 || (second[i] >>> 24) != 255) return false;
            if (Math.abs((first[i] & 255) - (second[i] & 255)) > 8
                    || Math.abs(((first[i] >>> 8) & 255) - ((second[i] >>> 8) & 255)) > 8
                    || Math.abs(((first[i] >>> 16) & 255) - ((second[i] >>> 16) & 255)) > 8) {
                if (++different > first.length / 100) return false;
            }
        }
        return true;
    }
}
