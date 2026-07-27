# HTTP/2 concurrency model

This document defines the concurrency and ownership rules for the direct HTTP/2
implementation. It is the design contract for the staged migration: completed
slices must preserve the rules they implement, and later changes must keep each
piece of mutable state within one of the ownership boundaries described here.

## Implementation status

The outbound coordinator, outbound flow-control ownership, stream-state
transitions, reset ordering, and separation of protocol lifetime from
application exchange lifetime are implemented. Connection setup and HTTP/2
readers use a connection executor that is independent of
`MuServerBuilder.withHandlerExecutor`; serialized HTTP/2 writer drains use
their own executor. Handler-executor rejection is handled as a 503 response on
the affected stream while the connection remains usable. Scheduled connection
maintenance and connection idle-timeout scans use a server timer that dispatches
due work to a separate maintenance executor.

SETTINGS acknowledgement expiry now runs on the server timer and is serialized
as a coordinator connection-error command; it no longer changes the socket
reader's blocking timeout. Peer SETTINGS are decoded into an immutable
reader-published snapshot, while their outbound effects (existing-stream flow
credit, the HPACK encoder limit, and the ACK) are applied in order by the
coordinator. Connection and stream inbound flow-control accounting is also
centralized in one component with a short-held lock. The reader atomically
debits both receive windows without depending on the blocking-output executor,
body consumers return freed credit through the same component, and the writer
publishes that credit as available immediately before emitting the corresponding
`WINDOW_UPDATE`. Live stream identity publication is similarly centralized in one
short-held registry lock, while the coordinator remains the sole owner of RFC
stream-state transitions.
Request-body read deadlines are monotonic timed waits owned by the body buffer:
they do not require a scheduled task or coordinator round trip. The reader,
coordinator, application executors, and timer now have separate execution
capacity. Accepted sockets, live-connection publication, and listener shutdown
admission share one lifecycle lock, so work queued on the connection executor
cannot start a connection after server shutdown. The deliberate cross-domain
boundaries are listed below.

## Goals

The model must:

* preserve HTTP/2 frame ordering and stream state rules;
* allow request handlers for different streams to run concurrently;
* support the existing blocking and asynchronous request/response APIs;
* keep protocol progress independent of handler-executor saturation;
* ensure every blocking or asynchronous operation completes or fails;
* avoid holding locks while invoking user code or performing socket writes; and
* use only JDK concurrency primitives.

## Execution domains

The executor allocation and application-turn rules in this section are
implemented. The ownership boundaries below describe both the current design
and the constraint on later phases; individual call sites can still be
simplified within those boundaries.

There are five execution domains and one timer facility:

* connection input;
* HTTP/2 output;
* connection maintenance;
* request handling and application continuations;
* asynchronous application I/O; and
* timer scheduling.

### Connection I/O

Before protocol selection, the listener lifecycle lock owns the transition from
an accepted socket to a published live connection. Shutdown closes accepted
sockets that have not crossed that boundary and prevents their queued connection
tasks from later being promoted. Live-connection retirement signals a condition
used by graceful shutdown; it is not discovered by polling.

Each HTTP/2 connection has:

* one blocking socket reader; and
* one protocol coordinator that also owns socket writes.

These tasks run independently of request handlers and independently of each
other. The connection reader is a long-lived task on the executor configured
by `MuServerBuilder.withConnectionExecutor`. It owns the input stream, read
buffer, HPACK decoder, wire-order validation, and monotonic inbound
`END_STREAM`/reset-seen fences. It directly debits receive credit and publishes
validated DATA to the request-body buffer. Making either operation wait for the
writer would let a blocked socket output stall input and can deadlock
bidirectional exchanges.

The coordinator serializes the RFC stream-state machine, outbound flow control,
pending frames, and socket output. The reader enqueues each inbound transition
before publishing an application-visible terminal event, but its wire-order
fences are intentionally not a second RFC state machine. The coordinator
schedules short serialized drains on the executor configured by
`MuServerBuilder.withHttp2WriterExecutor`; an idle or flow-control-blocked
connection does not retain a writer worker.

Timed connection work runs on
`MuServerBuilder.withConnectionMaintenanceExecutor`, not on a reader or writer.
The three executors have independent defaults. Sharing a bounded executor
between long-lived readers and work needed to make output or maintenance
progress can starve the latter and is unsupported.

### Application work

The executor configured by `MuServerBuilder.withHandlerExecutor` runs:

* request handlers;
* exception handlers; and
* response-completion listeners;
* serialized WebSocket callbacks; and
* application continuations such as JAX-RS asynchronous response processing.

The executor configured by `MuServerBuilder.withAsyncExecutor` runs
asynchronous request-body listeners, asynchronous response writes and their
callbacks, request-rejection notifications, and deprecated asynchronous
WebSocket adapters. Handler-oriented detached callbacks may use it as a
fallback when the handler executor rejects them. Operations whose contract
specifically requires the async executor fail when that executor rejects them.
A failure thrown by a request-body data listener is reported through its
`onError` callback in the same async application turn before the exchange is
failed. Request-rejection listener failures are isolated so one listener cannot
skip later listeners for the same rejected request.
A response write rejected by the async-completion terminal gate still dispatches
its failure callback to this executor; if the executor itself rejects that
notification, the caller delivers the required failure callback rather than
dropping it.

Neither application executor runs a connection reader, writer, or timer
callback. Rejection of a new handler is handled as rejection of that individual
request, not failure of the HTTP/2 connection. Rejection of continuation work
for an already accepted exchange completes or aborts that exchange explicitly;
it must not silently strand the stream.

Application callbacks execute in a per-server application turn. A turn records
both the requested executor and any fallback executor that actually accepted
the task. Work submitted from that turn to either occupied executor is appended
to a thread-confined FIFO and drained before the worker is released. It is not
recursively invoked and is not submitted back to an executor whose only worker
may be the current thread. Work targeting a distinct required executor is still
dispatched there.

This rule makes a shared single-worker non-queueing executor usable without
collapsing the handler and async domains when separate executors are configured.
The turn marker is installed only around application work and is always removed
before returning a caller-owned worker to its executor.

### Timers

A single server timer determines when scheduled work is due. The default is one
server-owned platform thread, and a custom
`ScheduledExecutorService` can be supplied through
`MuServerBuilder.withTimerExecutor`. Timer callbacks dispatch due connection
work to the maintenance executor and due application work to an application
executor. Periodic connection dispatch is coalesced so a delayed maintenance
executor does not accumulate duplicate work. Timer threads do not mutate
protocol state, perform socket I/O, or invoke user code.

Connection idle-timeout scans, WebSocket pings, and HTTP/2 SETTINGS
acknowledgement deadlines use this facility. SETTINGS expiry atomically wins
or loses a race with its ACK, then enqueues a connection-error command without
mutating protocol state on the timer thread. The writer registers each local
SETTINGS frame in the acknowledgement FIFO before writing any of its bytes, so
a fast ACK cannot be missed, but arms its deadline only after the frame is
flushed, so blocked output does not consume the peer's acknowledgement period.

Request-body read deadlines are intentionally not server timer tasks. They
bound an application thread's blocking read on `Http2BodyInputStream`, so the
body-buffer condition performs a monotonic timed wait. A zero request timeout
waits indefinitely, as required by the public builder contract. Expiry raises
the documented 408 response without the HTTP/1-only `Connection: close` field;
RFC 9113 Section 8.2.2 prohibits connection-specific fields in HTTP/2. If a
response has already started, the status cannot be replaced, so the coordinator
resets only that stream with `CANCEL`, meaning the stream is no longer needed
as defined by RFC 9113 Section 7.

## Ownership

| State or resource | Owner |
| --- | --- |
| Listener state, accepted socket admission, and live connection index | `ConnectionAcceptor` lifecycle lock |
| Socket input, input buffer, HPACK decoder | Connection reader |
| Connection shutdown and new-stream admission gate | `Http2Connection` state lock |
| RFC stream protocol state | Coordinator |
| Live application/rejected stream identity index | `Http2StreamRegistry` lock |
| Inbound END_STREAM and reset-seen fences | Connection reader, with monotonic publication where another domain reads them |
| Peer settings snapshot after decoding | Connection reader (volatile publication) |
| Outbound effects of peer settings | Coordinator |
| Local SETTINGS acknowledgement lifecycle | Writer registration and post-flush deadline publication; reader FIFO consumption; atomic ACK/timeout gate |
| Connection and stream outbound flow-control credit | Coordinator |
| Connection and stream inbound flow-control accounting | `Http2InboundFlowControl` lock |
| Pending outbound frames and their ordering | Coordinator |
| HPACK encoder and socket output | Coordinator |
| Request-body producer/consumer buffer and read deadline | `Http2BodyInputStream` lock and condition |
| Response API call ordering and async completion terminal gate | Application-side response/async handle lock |
| Response completion-listener registration and notification gate | `BaseResponse` completion-listener lock |
| Protocol stream completion | Coordinator |
| Application exchange completion | Serialized application completion path |

Coordinator-owned state uses ordinary collections and fields. It must not use
concurrent collections, volatile fields, or locks as substitutes for ownership.
Published diagnostic snapshots may use volatile or atomic fields, but they are
not authoritative protocol state.

The live identity index is not a second stream state machine. It answers whether
a live peer stream currently names an application exchange, a rejected request
body that still needs draining, or nothing. A single registry operation
atomically converts an accepted stream to a rejected-body stream when handler
submission is refused; connection shutdown and diagnostics cannot observe an
artificially empty gap between those identities.

## Coordinator commands

Commands can be produced by the reader, application threads, body consumers,
timer, or server shutdown. They are placed in a multiple-producer,
single-consumer mailbox.

Command categories include:

* protocol transitions and outbound effects of decoded inbound frames;
* outbound headers, data, and informational responses;
* application handler completion or failure;
* WINDOW_UPDATE output produced after request-body credit is returned;
* local graceful or forced shutdown; and
* timeout expiry.

Commands that have a caller waiting for an outcome carry a promise. Completing a
promise must only signal the waiter; arbitrary user continuations must not run on
the coordinator. Asynchronous callbacks are dispatched on the application
executor.

The coordinator never waits for:

* a request handler;
* a request-body consumer;
* flow-control credit;
* another coordinator command; or
* a callback scheduled on the application executor.

A socket write can block because the implementation uses blocking I/O. While a
write is in progress, bytes from that write precede commands that have not yet
been processed by the coordinator.

## Stream protocol state

RFC 9113 stream state is represented independently of application exchange
lifecycle. Initially supported states are:

* `OPEN`;
* `HALF_CLOSED_LOCAL`;
* `HALF_CLOSED_REMOTE`; and
* `CLOSED`.

Only coordinator processing can transition this state. Inbound `END_STREAM`
and peer `RST_STREAM` commands are one-way. The reader enqueues a valid
`END_STREAM` before making its EOF visible to the request handler, so a response
awakened by EOF is necessarily ordered after the inbound transition. Local
`END_STREAM` is reserved when the coordinator accepts its outbound frame.

The reader records monotonic "remote end seen" and "reset seen" fences on the
stream. This is input-ordering state, not a protocol-state transition: it only
prevents later wire-ordered DATA or trailers from reaching the request body
before the coordinator applies the corresponding transition.

An outbound frame command is validated and its state transition reserved by the
coordinator before it becomes pending for socket output. A reset closes the
stream, fails or discards all unsent frames for that stream, and prevents later
commands from being accepted. The exception is an additional `RST_STREAM`
generated in response to a frame that arrives on the closed stream, as permitted
by RFC 9113 Section 5.4.2.

`RST_STREAM` is therefore the final non-priority frame emitted for a stream,
apart from those permitted additional resets. Frames already written before the
reset command is processed remain validly ordered before the reset.

Applying a peer reset can close protocol state, error the request-body buffer,
mark the response cancelled, and signal an async handler waiter on the
coordinator because none of those operations invokes user code. Completion
listeners and other application callbacks continue on the handler task. Unread
DATA is returned to the connection window and advertised with `WINDOW_UPDATE`;
the peer cannot reuse that credit until it receives the update. RFC 9113
Section 6.9.1 requires a sender not to exceed the space advertised by the
receiver and states that the connection window can only change through
`WINDOW_UPDATE`.

Reader-detected connection errors are also ordered coordinator commands. The
command closes every protocol stream, fails pending writes, and makes the error
`GOAWAY` the only writable frame before body cancellation can wake application
work. A handler therefore cannot emit response frames after that `GOAWAY`.
Reader-detected stream errors follow the same rule: the reader records its reset
fence and enqueues `RST_STREAM` before body cancellation can wake the handler.

## Flow control

Both connection and stream outbound credit are checked and reserved together by
the coordinator when DATA is selected for output. Handler threads do not
withdraw credit and do not wait on flow-controller conditions.

If insufficient credit exists, DATA remains pending while the coordinator
continues processing control frames and other streams. A reset removes pending
DATA for that stream and fails its promise. A later `WINDOW_UPDATE` cannot make
discarded DATA writable.

Flow-control commands from the reader are one-way. If a window increment
overflows, the coordinator queues the required `RST_STREAM` or `GOAWAY` itself;
the reader does not wait for the coordinator to validate the command.

Inbound DATA is charged before the reader exposes its payload to the
request-body buffer. One short-held lock makes the connection and stream debit
atomic with credit returned by body-consumer threads and its later publication
by the writer. Debiting does not require a write-coordinator round trip: the
socket writer can block, and input must continue reading and processing HTTP/2
frames promptly as required by RFC 9113 Section 5.2.2. Freed credit is not
available for another DATA debit while its `WINDOW_UPDATE` is merely queued,
because Section 6.9.1 defines the sender's limit in terms of the window
advertised by the receiver. The writer publishes the credit immediately before
writing the frame. Raw JDK socket output can expose the frame to the peer while
the blocking write call is still in progress, so publication after that call
can incorrectly reject DATA from a compliant peer. Conversely, the socket API
provides no local boundary that distinguishes such DATA from premature DATA
sent before the peer observed the update. The implementation chooses
interoperability at that unavoidable boundary: early availability is bounded by
credit the application has actually freed, and a write failure closes the
connection. This differs from the local `END_STREAM` fence, whose early
publication can independently over-admit new peer streams. A body consumer
commits its buffer offset and
releases the body lock before returning credit to the inbound component or
queuing `WINDOW_UPDATE` output. Coordinator contention therefore cannot retain
the body-buffer lock.

Freed credit is recorded exactly once when bytes are consumed or discarded.
The inbound component batches `WINDOW_UPDATE` increments at half of the
advertised window and keeps connection-level accounting synchronized even when
DATA is rejected with a stream error, as required by RFC 9113 Section 6.9.
When the server resets one stream, unread queued body data is discarded and
queued for return to the connection window; the closed stream does not receive
a `WINDOW_UPDATE`. Once the connection update is written, a stream-local
failure cannot consume credit needed by unrelated streams.

## Exchange lifecycle

The following are distinct events:

* request HEADERS accepted;
* peer END_STREAM received;
* request body consumed or discarded;
* handler started;
* handler completed or failed;
* local response END_STREAM accepted;
* local response END_STREAM written;
* stream reset;
* protocol stream closed; and
* completion listeners notified.

A handler finishing does not remove the stream.

The writer publishes the reader-facing local `END_STREAM` fence after handing
the complete terminal frame to the output stream and before flushing it. This
keeps the stream counted while an output write is blocked before making any
progress, while avoiding a flush-time interval in which the peer can observe the
frame before the reader sees the fence. Protocol closure, write promises, and
successful response completion still advance only after the flush succeeds; a
failed terminal write closes the connection.

If a handler completes a response while the peer request side is still open,
the input enters discard mode. Already-buffered and future request DATA is
discarded with both connection and stream credit returned. This allows a client
whose request is larger than the initial stream window to reach END_STREAM
instead of leaving the half-closed stream permanently flow-control blocked. No
discarded payload is retained. The stream remains in the protocol registry until
peer END_STREAM or reset.

RFC 9113 Section 8.1 permits a server to complete a response before receiving
the entire request and permits, but does not require, a subsequent
`RST_STREAM(NO_ERROR)`. The current compatibility policy retains the stream
instead of resetting it and consumes and discards the remaining request. Since
the server is freeing that capacity, it returns both windows as described in
Section 6.9.1. A future explicit `NO_ERROR` reset policy would be a separate,
observable protocol choice.

Application-active request reporting is independent of that registry. A
half-closed stream still counts towards `SETTINGS_MAX_CONCURRENT_STREAMS` after
its handler ends; a closed stream does not count while its handler or completion
work finishes. RFC 9113 Section 5.1.2 defines the limit in terms of open and
half-closed protocol streams, not handler tasks.

Completion listeners are notified exactly once, outside the coordinator, after
the outcome is known. A successful outcome requires the request to have reached
EOF or successful discard and the response END_STREAM to have been written.
Failure from one listener is logged and isolated so it cannot strand later
listeners or prevent the notification gate from reaching its completed state.

## Locking and signalling

Permitted cross-thread primitives are deliberately narrow:

* a blocking mailbox for coordinator commands;
* a small promise backed by a latch and a result or error;
* one listener lifecycle lock and condition for accepted/live connection
  admission and graceful-shutdown draining;
* one short-held connection state lock for shutdown, admission, and atomic
  publication across the protocol components;
* one short-held lock for atomic connection-and-stream inbound flow-control
  accounting;
* one short-held lock for the live stream identity index;
* the request-body buffer lock and condition, including its monotonic blocking
  read deadline;
* an application-side lock that orders async response submissions with the
  terminal completion gate, so completion observes every accepted write and
  rejects every later write; and
* one short-held response lock that orders completion-listener registration
  against the notification snapshot; and
* a thread-confined FIFO that drains nested application work without recursive
  calls or executor resubmission; and
* atomics for idempotent resource closure.

RFC stream state, outbound flow-control windows, and pending-write queues are
not protected by independent locks because they have a single owner. The live
identity index is cross-thread publication rather than RFC stream state, and
therefore uses its explicit registry lock. Inbound receive windows are the
other deliberate lock-based boundary: DATA debits happen on the reader while
credit is returned by body-consumer threads, so both windows share one lock.
The connection state lock may enter the registry, inbound-flow component, or
coordinator mailbox while publishing one transition. None of those components
enters the connection state lock while holding its own lock.

Graceful HTTP/2 shutdown uses the same state-lock boundary for stream admission
and the final accepted-stream snapshot. The in-flight stream-creation grace
period begins only after the writer flushes the initial maximum-stream-ID
`GOAWAY`; writer scheduling or socket delay therefore cannot consume the grace
period before the peer sees the warning. Its deadline uses monotonic time.

No lock is held while:

* writing to a socket;
* invoking a handler, listener, callback, or exception handler; or
* waiting for another thread.

## Required invariants

These are required end-state invariants. Full single-owner enforcement in
invariant 2, and invariants 9 and 10, depend on the future ownership and
execution-domain phases identified above.

1. Inbound frame events from the reader are processed in wire order.
2. Every protocol state mutation has an explicit linearization point: the
   coordinator for stream and output state, and the inbound-flow lock for
   receive-window accounting. Live stream identity changes linearize at the
   registry lock.
3. No frame other than permitted priority information or a permitted additional
   `RST_STREAM` is emitted after `RST_STREAM`.
4. No DATA is emitted unless both connection and stream credit were reserved.
5. Reset or connection failure completes every affected pending write with an
   error.
6. A stream remains addressable until it is protocol-closed.
7. One stream's flow-control stall or error does not prevent control frames or
   unrelated writable streams from progressing.
8. Completion notification occurs exactly once.
9. User code never runs on connection I/O or timer threads.
10. Handler-executor rejection cannot strand or terminate unrelated streams.

## Validation strategy

Pure state-machine and scheduler tests are the primary proof. They enumerate
stream transitions and directly test pending-write, reset, and flow-control
ordering without thread timing.

Socket integration tests then cover:

* response frames racing an inbound stream error;
* flow-controlled DATA followed by reset and later credit;
* early responses with a still-open request side;
* resets while handlers are reading or writing;
* multiple streams with independent progress;
* handler-executor saturation and rejection (after execution-domain separation);
* graceful and forced connection shutdown; and
* completion callback state and exactly-once behaviour.

Repeated stress tests supplement these deterministic tests but do not replace
them.
