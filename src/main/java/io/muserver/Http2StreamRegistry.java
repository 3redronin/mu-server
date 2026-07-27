package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes the identity associated with each live peer-initiated stream.
 *
 * <p>This is not the HTTP/2 stream state machine, which is owned by
 * {@link Http2WriteCoordinator}. The reader, coordinator, application threads,
 * and connection diagnostics all need to find a live application exchange,
 * while rejected requests have protocol lifetime without an application
 * exchange. One short-held lock makes those identities mutually exclusive and
 * makes accepted-to-rejected conversion atomic.</p>
 */
final class Http2StreamRegistry {

    static final class Lookup {
        private static final Lookup ABSENT = new Lookup(null, false);
        private static final Lookup REJECTED = new Lookup(null, true);

        private final @Nullable Http2Stream applicationStream;
        private final boolean rejectedRequestBody;

        private Lookup(
            @Nullable Http2Stream applicationStream,
            boolean rejectedRequestBody
        ) {
            this.applicationStream = applicationStream;
            this.rejectedRequestBody = rejectedRequestBody;
        }

        @Nullable Http2Stream applicationStream() {
            return applicationStream;
        }

        boolean rejectedRequestBody() {
            return rejectedRequestBody;
        }
    }

    private final Lock lock = new ReentrantLock();
    private final Map<Integer, Lookup> entries = new HashMap<>();

    void registerApplicationStream(Http2Stream stream) {
        lock.lock();
        try {
            if (entries.putIfAbsent(stream.id, new Lookup(stream, false)) != null) {
                throw new IllegalStateException(
                    "Stream identity already exists for " + stream.id
                );
            }
        } finally {
            lock.unlock();
        }
    }

    void registerRejectedRequestBody(int streamId) {
        requirePositiveStreamId(streamId);
        lock.lock();
        try {
            if (entries.putIfAbsent(streamId, Lookup.REJECTED) != null) {
                throw new IllegalStateException(
                    "Stream identity already exists for " + streamId
                );
            }
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("ReferenceEquality")
    void convertApplicationStreamToRejectedRequestBody(Http2Stream stream) {
        lock.lock();
        try {
            Lookup current = entries.get(stream.id);
            if (current == null || current.applicationStream() != stream) {
                throw new IllegalStateException(
                    "Application stream identity does not exist for " + stream.id
                );
            }
            entries.put(stream.id, Lookup.REJECTED);
        } finally {
            lock.unlock();
        }
    }

    Lookup lookup(int streamId) {
        lock.lock();
        try {
            Lookup entry = entries.get(streamId);
            if (entry == null) {
                return Lookup.ABSENT;
            }
            return entry;
        } finally {
            lock.unlock();
        }
    }

    @Nullable Http2Stream applicationStream(int streamId) {
        lock.lock();
        try {
            Lookup entry = entries.get(streamId);
            return entry == null ? null : entry.applicationStream();
        } finally {
            lock.unlock();
        }
    }

    boolean containsApplicationStream(int streamId) {
        lock.lock();
        try {
            Lookup entry = entries.get(streamId);
            return entry != null && entry.applicationStream() != null;
        } finally {
            lock.unlock();
        }
    }

    boolean removeRejectedRequestBody(int streamId) {
        lock.lock();
        try {
            Lookup entry = entries.get(streamId);
            if (entry == null || !entry.rejectedRequestBody()) {
                return false;
            }
            entries.remove(streamId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("ReferenceEquality")
    void removeApplicationStream(Http2Stream stream) {
        lock.lock();
        try {
            Lookup current = entries.get(stream.id);
            if (current != null && current.applicationStream() == stream) {
                entries.remove(stream.id);
            }
        } finally {
            lock.unlock();
        }
    }

    List<Http2Stream> applicationStreams() {
        lock.lock();
        try {
            List<Http2Stream> result = new ArrayList<>(entries.size());
            for (Lookup entry : entries.values()) {
                Http2Stream stream = entry.applicationStream();
                if (stream != null) {
                    result.add(stream);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    long concurrentStreamCount() {
        lock.lock();
        try {
            long result = 0;
            for (Lookup entry : entries.values()) {
                Http2Stream stream = entry.applicationStream();
                if (stream == null || stream.countsTowardsMaxConcurrentStreams()) {
                    result++;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    boolean hasActiveProtocolStreams() {
        return concurrentStreamCount() != 0;
    }

    boolean isEmpty() {
        lock.lock();
        try {
            return entries.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private static void requirePositiveStreamId(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A stream ID must be positive");
        }
    }
}
