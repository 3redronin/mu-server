# HTTP/2 concurrency model

This document defines the target concurrency and ownership rules for the direct
HTTP/2 implementation. It is the design contract for a staged migration, not an
inventory of guarantees that have all landed: completed slices must preserve the
rules they implement, and later changes must move the remaining code towards
this model.

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
reader's blocking timeout. Still to be implemented are migration of the
remaining connection settings, stream registry, inbound flow-control
accounting, and request-body read deadlines to coordinator ownership. The
reader and coordinator have separate execution capacity from handlers, but do
not yet have all of the final single-owner boundaries described below.

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
implemented. Some finer-grained protocol ownership rules remain the target for
later phases, as called out below.

There are five execution domains and one timer facility:

* connection input;
* HTTP/2 output;
* connection maintenance;
* request handling and application continuations;
* asynchronous application I/O; and
* timer scheduling.

### Connection I/O

Each HTTP/2 connection has:

* one blocking socket reader; and
* one protocol coordinator that also owns socket writes.

These tasks run independently of request handlers and independently of each
other. The connection reader is a long-lived task on the executor configured
by `MuServerBuilder.withConnectionExecutor`. It owns the input stream, read
buffer, and HPACK decoder. The target end state is for it to turn every logical
frame into a coordinator command without mutating connection or stream protocol
state; migration of the remaining reader-owned protocol mutations is not
complete.

The coordinator already serializes outbound protocol state and is the only code
that writes to the socket. It schedules short serialized drains on the executor
configured by `MuServerBuilder.withHttp2WriterExecutor`; an idle or
flow-control-blocked connection does not retain a writer worker. The coordinator
will become the sole owner of all connection and stream protocol state as the
remaining state is migrated.

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
mutating protocol state on the timer thread. Request-body read deadlines remain
local blocking deadlines for now.

## Ownership

| State or resource | Owner |
| --- | --- |
| Socket input, input buffer, HPACK decoder | Connection reader |
| Stream map and stream protocol state | Coordinator |
| Local and peer settings after decoding | Coordinator |
| Connection and stream outbound flow-control credit | Coordinator |
| Connection and stream inbound flow-control accounting | Coordinator |
| Pending outbound frames and their ordering | Coordinator |
| HPACK encoder and socket output | Coordinator |
| Request-body producer/consumer buffer | `Http2BodyInputStream` lock |
| Response API call ordering | Application-side response/async handle |
| Connection and exchange completion | Coordinator |

Coordinator-owned state uses ordinary collections and fields. It must not use
concurrent collections, volatile fields, or locks as substitutes for ownership.
Published diagnostic snapshots may use volatile or atomic fields, but they are
not authoritative protocol state.

## Coordinator commands

Commands can be produced by the reader, application threads, body consumers,
timer, or server shutdown. They are placed in a multiple-producer,
single-consumer mailbox.

Command categories include:

* decoded inbound frames;
* outbound headers, data, and informational responses;
* application handler completion or failure;
* request-body credit returned or body abandoned;
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

Until all inbound stream frames are coordinator commands, the reader records
monotonic "remote end seen" and "reset seen" fences on the stream. This is
input-ordering state, not a protocol-state transition: it only prevents later
wire-ordered DATA or trailers from reaching the request body before the
coordinator applies the corresponding transition.

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
listeners and other application callbacks continue on the handler task.

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

Inbound DATA is charged when its frame command is processed. Credit is returned
exactly once when bytes are consumed or discarded. Body consumers post
credit-return commands rather than mutating flow controllers directly.

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

If a handler completes a response while the peer request side is still open,
the input enters discard mode. Already-buffered and future request DATA is
discarded with connection credit returned. Stream credit is not returned: this
bounds the amount of additional data that the peer can send on an abandoned
request while preserving connection capacity for other streams. The stream
remains in the protocol registry until peer END_STREAM or reset.

RFC 9113 Section 8.1 permits a server to complete a response before receiving
the entire request and permits, but does not require, a subsequent
`RST_STREAM(NO_ERROR)`. The current policy retains the stream instead of
resetting it, allowing already-in-flight request frames to be processed while
the withheld stream credit bounds further transmission on that abandoned
request.

Application-active request reporting is independent of that registry. A
half-closed stream still counts towards `SETTINGS_MAX_CONCURRENT_STREAMS` after
its handler ends; a closed stream does not count while its handler or completion
work finishes. RFC 9113 Section 5.1.2 defines the limit in terms of open and
half-closed protocol streams, not handler tasks.

Completion listeners are notified exactly once, outside the coordinator, after
the outcome is known. A successful outcome requires the request to have reached
EOF or successful discard and the response END_STREAM to have been written.

## Locking and signalling

Permitted cross-thread primitives are deliberately narrow:

* a blocking mailbox for coordinator commands;
* a small promise backed by a latch and a result or error;
* the existing request-body buffer lock and condition;
* an application-side lock that orders async response submissions; and
* a thread-confined FIFO that drains nested application work without recursive
  calls or executor resubmission; and
* atomics for idempotent resource closure.

Protocol state, stream maps, flow-control windows, and pending-write queues are
not protected by independent locks because they have a single owner.

No lock is held while:

* writing to a socket;
* invoking a handler, listener, callback, or exception handler; or
* waiting for another thread.

## Required invariants

These are required end-state invariants. Full single-owner enforcement in
invariant 2, and invariants 9 and 10, depend on the future ownership and
execution-domain phases identified above.

1. Inbound frame events from the reader are processed in wire order.
2. Every protocol state mutation has the coordinator as its linearization point.
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
