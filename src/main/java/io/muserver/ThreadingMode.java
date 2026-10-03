package io.muserver;

/** Selects the thread implementation for Mu-owned application and internal I/O executors. */
public enum ThreadingMode {
    /** Virtual threads on Java 25 or later; cached platform threads on earlier runtimes. */
    AUTO,
    /** Cached platform threads, with no configured maximum. */
    PLATFORM,
    /** A new virtual thread per task. Requires Java 21 or later. */
    VIRTUAL
}
