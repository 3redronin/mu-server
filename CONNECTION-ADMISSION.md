# Connection admission and listen backlog

`MuServerBuilder.withMaxConnections(int)` limits admitted connections across all HTTP and HTTPS listeners of one server. Zero is unlimited by default; negative values are rejected. Admission occurs before TLS or PROXY setup. Idle keep-alive connections and upgraded WebSockets retain a slot; HTTP/2 streams share their connection's slot. Retirement releases the slot idempotently after socket closure.

At capacity, listeners pause acceptance and additional clients may wait in the operating system's listen queue. Idle listeners do not reserve slots while waiting in `accept()`. Concurrent listeners can race for the last slot; an excess accepted candidate closes before setup and increments the overload-rejection counter. The limit excludes the OS backlog and transient rejected candidates. Overload can produce connection delay, refusal/reset or timeout rather than HTTP 503.

Admission uses a short `ReentrantLock` critical section with no socket I/O under the lock. Unlimited admission avoids an additional per-socket tracking set. The request-processing limit remains independent: it does not cap idle connections or WebSockets. Neither admission setting bounds pending output or total process memory.

`withListenBacklog(int)` sets the requested TCP backlog independently for each listener. The default remains 50; values must be positive. The getter reports the requested value, which the operating system may cap or ignore. This is not a socket-buffer setting.

Connection/admission tests cover setup, established connections, HTTP/2, WebSockets, multiple listeners, timeouts, disconnect, rejection and shutdown. No exact portable OS queue capacity or measured memory envelope is claimed.
