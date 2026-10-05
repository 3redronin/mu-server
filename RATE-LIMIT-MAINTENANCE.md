# Rate-limit history maintenance

Mu schedules one coalesced cleanup task per server with configured rate limiters, approximately once per second. The timer dispatches work to the internal executor; it does not traverse bucket history itself. Servers without rate limiters create no cleanup task.

Maintenance traverses a concurrent key view and acquires the existing decision lock separately for each bucket. It removes at most 1,024 expired entries per lock acquisition and releases the lock between batches. The current queue is looked up while holding the lock, preserving history renewed by concurrent requests.

Cleanup does not require more requests or a call to `currentBuckets()`. That method retains its immediate filtered-snapshot behavior. Shutdown signals queued and running sweeps to stop between batches and shuts down the timer through the existing resource lifecycle. Submission rejection does not permanently prevent a later sweep.

Busy workers can delay cleanup. Sweep cost scales with bucket count and expired history. This change does not bound live bucket cardinality, retained map-table capacity or total process memory.

`RateLimiterCleanupTest` uses a fake clock to check idle expiry, signed `nanoTime` wraparound, live and renewed history, decision progress between batches, coalescing, rejection recovery and shutdown. These are correctness checks, not performance or capacity measurements.
