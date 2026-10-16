package io.muserver;

/**
 * Reader-visible prefix of one write that has drained through the transport, including TLS
 * ciphertext. Only that write's serialized transport owner advances it; observers do not own
 * the source buffer or complete the write. Its notification only schedules protocol work.
 */
final class TransportWriteProgress {
    private volatile int consumed;
    private int ownerConsumed;
    private volatile int wakeAt;
    private final Runnable continuation;

    TransportWriteProgress(Runnable continuation) { this.continuation = continuation; }

    void consumed(int bytes) { ownerConsumed += bytes; consumed = ownerConsumed; }

    boolean reached(int offset) { return consumed >= offset; }

    void awaitOffset(int offset) { wakeAt = offset; }

    boolean ready() { int offset = wakeAt; return offset > 0 && reached(offset); }

    /** Called outside transport locks; the continuation must not block, throw or run application code. */
    void notifyOwner() { if (ready()) continuation.run(); }
}
