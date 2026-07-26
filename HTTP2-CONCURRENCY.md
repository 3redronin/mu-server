# HTTP/2 concurrency model

This document defines the concurrency and ownership rules for the direct HTTP/2
implementation. It is an implementation contract: changes to HTTP/2 connection,
stream, flow-control, response, or shutdown code must preserve these rules.

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

There are two execution domains and one timer facility.

### Connection I/O

Each HTTP/2 connection has:

* one blocking socket reader; and
* one protocol coordinator that also owns socket writes.

These tasks run independently of request handlers. The connection reader owns
only input parsing state: the input stream, read buffer, and HPACK decoder. It
turns logical frames into coordinator commands and does not mutate connection or
stream protocol state.

The coordinator is the sole owner of connection and stream protocol state. It
processes commands serially and is the only code that writes to the socket.

Connection I/O uses a server-owned executor whose default provides prompt
execution for long-lived tasks: virtual threads where available, otherwise a
separate cached thread pool. An arbitrary bounded executor is not initially
exposed because starving either half of a connection can deadlock it.

### Application work

The executor configured by `MuServerBuilder.withHandlerExecutor` runs user and
application work:

* request handlers;
* asynchronous request-body listeners;
* asynchronous response work and callbacks;
* exception handlers; and
* response-completion listeners.

It never runs a connection reader or coordinator. Rejection by this executor is
handled as rejection of an individual request, not failure of the HTTP/2
connection.

### Timers

A single server timer schedules timeout commands into connection coordinators.
Timer threads do not mutate protocol state and do not invoke user code.

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

Only coordinator processing can transition this state.

An outbound frame command is validated and its state transition reserved by the
coordinator before it becomes pending for socket output. A reset closes the
stream, fails or discards all unsent frames for that stream, and prevents later
commands from being accepted. The exception is an additional `RST_STREAM`
generated in response to a frame that arrives on the closed stream, as permitted
by RFC 9113 Section 5.4.2.

`RST_STREAM` is therefore the final non-priority frame emitted for a stream,
apart from those permitted additional resets. Frames already written before the
reset command is processed remain validly ordered before the reset.

## Flow control

Both connection and stream outbound credit are checked and reserved together by
the coordinator when DATA is selected for output. Handler threads do not
withdraw credit and do not wait on flow-controller conditions.

If insufficient credit exists, DATA remains pending while the coordinator
continues processing control frames and other streams. A reset removes pending
DATA for that stream and fails its promise. A later `WINDOW_UPDATE` cannot make
discarded DATA writable.

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
discarded with credit returned. The stream remains active until peer END_STREAM
or reset. This matches the `ResponseInfo.completedSuccessfully()` contract that
the request was fully read and the response was fully sent.

Completion listeners are notified exactly once, outside the coordinator, after
the outcome is known. A successful outcome requires the request to have reached
EOF or successful discard and the response END_STREAM to have been written.

## Locking and signalling

Permitted cross-thread primitives are deliberately narrow:

* a blocking mailbox for coordinator commands;
* a small promise backed by a latch and a result or error;
* the existing request-body buffer lock and condition;
* an application-side lock that orders async response submissions; and
* atomics for idempotent resource closure.

Protocol state, stream maps, flow-control windows, and pending-write queues are
not protected by independent locks because they have a single owner.

No lock is held while:

* writing to a socket;
* invoking a handler, listener, callback, or exception handler; or
* waiting for another thread.

## Required invariants

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
* handler-executor saturation and rejection;
* graceful and forced connection shutdown; and
* completion callback state and exactly-once behaviour.

Repeated stress tests supplement these deterministic tests but do not replace
them.
