package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.Date;

/** Reuses the generated Date field, whose wire value has whole-second resolution. */
final class HttpDateCache {
    private static final HttpDateCache INSTANCE = new HttpDateCache();
    private volatile @Nullable Entry cached;

    static HeaderString now() {
        return INSTANCE.at(System.currentTimeMillis());
    }

    HeaderString at(long millis) {
        long second = Math.floorDiv(millis, 1000);
        Entry previous = cached;
        if (previous != null && previous.second == second) {
            return previous.value;
        }
        // Racing callers can format the same second. No request waits for another
        // thread, and the immutable pair is safely published in one volatile write.
        Entry updated = new Entry(second, ValidatedHeaderValue.normalized(Mutils.toHttpDate(new Date(millis))));
        cached = updated;
        return updated.value;
    }

    private static final class Entry {
        final long second;
        final HeaderString value;

        Entry(long second, HeaderString value) {
            this.second = second;
            this.value = value;
        }
    }
}
