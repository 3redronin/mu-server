# Mu4 nonblocking IO investigation

Mu4 can reduce the number of waiting transport workers without replacing its blocking handler APIs. The most useful architecture to evaluate is nonblocking socket and TLS transport, resumable protocol processing, and application workers that retain the current request and response streams. The biggest benefits should be idle connections, SSE, WebSockets, slow uploads, and slow response consumers. These are expected benefits from the ownership model, not measured NIO performance results.

The difficult parts are buffer ownership, backpressure, partial output, TLS progression, and connection lifecycle. HTTP/1 already has a stateful parser worth retaining. HTTP/2 already has a serialized output coordinator worth retaining. Neither connection implementation can currently run on a shared event loop because both perform blocking reads and waits.

Source baseline: `50ad1a7072fffe959770531204a09b2ddd3b4637`, inspected on 10 October 2026. Investigation branch: `investigate/nonblocking-io`. The ownership map and migration analysis below describe that baseline. The hybrid-transport measurements come from the saved 5 October experiment at the same source baseline. The decoder extraction, test-only plaintext prototype, production transport-control boundary and bounded stream bridges on this branch are described below. Production connections continue to use blocking socket IO and the existing thread policy.

## Separate transport scaling from application pinning

There are three related concerns:

1. **Platform thread cost.** Blocking network readers occupy native threads while connections are idle. Blocking output and asynchronous body readers can require additional workers. The Java 11 and 17 fallback uses cached platform pools without a configured maximum.
2. **Java 21 application pinning.** A handler can block inside an application monitor or stock stream wrapper and exhaust virtual-thread carriers. Moving socket progression onto platform event loops can release a wait that depends on Mu transport, but cannot guarantee that every other application task gets a carrier.
3. **Retained memory.** Replacing threads with queues does not bound queued bytes, callback closures, protocol state, TLS buffers, or socket memory. Memory admission and producer backpressure remain necessary.

Java 21 socket blocking normally allows virtual threads to unmount; monitor-held blocking is a separate limitation. Java 24 removed monitor-induced pinning, so Java 25 is a reasonable LTS threshold for a conservative default. Native or foreign calls can still pin on newer runtimes. See the [Java 21 virtual thread guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html) and [Java 24 release notes](https://www.oracle.com/java/technologies/javase/24-relnote-issues.html).

The current `MuServerBuilder.defaultExecutor()` and `ThreadingMode.AUTO` select virtual threads on Java 21 and later. `ExecutionResources.create()` applies that policy to both application and internal executors. Separate virtual executors do not establish separate carrier schedulers. A default-policy change needs its own compatibility decision and tests. Under a NIO design, platform event loops should progress independently of the application's virtual-thread policy; `ThreadingMode` would need a clearly documented meaning for the remaining application and auxiliary workers.

The saved [hybrid transport evaluation](../mu-conformance/experiments/hybrid-transport-20261005/report.txt) is particularly relevant. Its prototype used platform internal workers and virtual application workers on Java 21–23. At 256 verified idle keepalive connections, [raw snapshots](../mu-conformance/experiments/hybrid-transport-20261005/idle.json) recorded:

| Protocol | Existing virtual transport OS threads | Platform internal workers OS threads | Existing thread memory committed | Platform internal workers thread memory committed |
| --- | ---: | ---: | ---: | ---: |
| HTTP/1 | 30 | 289 | 2.1 MiB | 29.6 MiB |
| HTTP/2 | 37 | 289 | 2.4 MiB | 30.0 MiB |

These are process snapshots with other runtime threads included, not maximum-capacity measurements. The platform reader cost rose approximately with connection count. A deterministic sibling test also showed a ready response unable to finish its application continuation while another handler occupied the sole carrier, despite independent transport completing its DATA write. This is why transport independence and application scheduling need separate acceptance criteria.

## Current IO and ownership map

| Area and concrete source | Current blocking behavior | NIO implication |
| --- | --- | --- |
| `ConnectionAcceptor.acceptLoop()` and `handleClientSocket()` | One acceptor thread per listener; one internal task per accepted socket. Setup reads PROXY, performs `SSLSocket.startHandshake()`, and identifies HTTP before entering the connection loop. | Replace accepted socket ownership with channel registration and resumable setup. The acceptor's fixed thread count is a minor cost; per-connection setup and readers are the main target. |
| `BaseHttpConnection.start()` | Lifetime is structured around a blocking method with stream closure in the acceptor's `finally`. Socket types also supply addresses, TLS session details, and forced close. | Introduce transport lifecycle and metadata independent of `Socket` and a blocking stack frame. |
| `Http1Connection.start()` | Pulls headers, dispatches a handler, waits for handler/async completion, drains an unread body, then starts the next request. | Use completion-driven exchange states. Keep sequential HTTP/1 exchanges initially. No handler or completion wait on an event loop. |
| `Http1MessageParser` and `Http1BodyStream` | Parser pulls from `InputStream`; application body reads advance the same parser. Body messages alias the parser's 8 KiB array. | Separate byte feeding from parsing; give the protocol owner sole parser access. Deliver body data through an explicitly owned queue or listener. |
| `Http1Response` and framing streams | Response status, headers, body framing and flushing write directly through `socketOut`. Error/rejection cleanup also writes output. | Keep framing semantics, but make underlying output ordered and resumable. Blocking application adapters wait off the event loop. |
| `Http2Connection.start()`, `Http2Handshaker`, `Http2HeadersFrame.readLogicalFrame()` | `Mutils.readAtLeast()` waits for preface, frame bytes, and continuation frames. The reader later waits for writer/application shutdown. | Explicit preface, frame-header, payload and continuation states; completion-driven retirement. |
| `Http2WriteCoordinator`, `Http2Connection.drainWritableFrames()` and `Http2WriteBatch` | Commands and stream fairness are already coordinated; drain tasks write and flush through blocking output. Idle writer tasks return, rather than reserving a permanent worker. | Retain coordination and scheduling rules; replace the transport sink and its completion/accounting model. Slow socket writes are still a worker cost today. |
| `Http2BodyInputStream` and `Http2InboundFlowControl` | Bodies already use queued DATA and condition waits. Credit returns with consumption/discard. | Reuse the blocking application bridge and flow-control accounting; add direct listener delivery and retain reader independence. |
| `Mu3AsyncHandleImpl` and `AsyncResponseOutput` | Async body reads schedule `clientIn.read()` on an internal worker. Async output serializes calls to a blocking response stream. | Async should subscribe to body availability and output completion without parking internal readers or writers. Public callbacks still use application workers. |
| `WebsocketConnection` and `MuWebSocketSession` | Lifetime read loop, `readAtLeast()` calls, and waits for application event completion. Sends write under a lock; async sends submit internal tasks. | Resumable frame decoding, bounded receive demand, and one ordered send queue per session. |
| Multipart, JAX-RS providers, compression, static resources | Consume/produce streams, perform serialization/compression, and may access files. | Keep on application or auxiliary workers. Transport conversion does not make disk IO, serialization, JDBC or arbitrary dependencies nonblocking. |

Existing [resource ownership and limits](RESOURCE-LIMITS.md) and [HTTP/2 concurrency](HTTP2-CONCURRENCY.md) describe invariants that should survive migration. Current connection admission covers setup through upgraded WebSockets, while request admission does not cap idle connections. Preserve this distinction.

## HTTP parsing changes

### HTTP/1

`Http1MessageParser` already retains its `ParseState`, token buffer, length counters, chunk state and trailers between reads. Preserve those grammar and validation rules. The boundary to extract is the refill in `readNextMessage()`, which currently calls `source.read(readBuffer)` whenever input is exhausted.

A transport-neutral decoder should consume supplied bytes and distinguish a decoded event, need for more bytes, downstream pause, clean EOF, and malformed/truncated input. Exact method names can follow a small prototype. A temporary lack of bytes must not be represented by EOF or a parser exception: `readNext()` currently marks the parser permanently failed on exceptions. Keep a blocking driver around the decoder for existing parser tests and any remaining stream consumers.

`sendContent()` returns `MessageBodyBit` pointing into the reusable input array. Today reads and consumption constrain reuse. With independent network progression, refilling or compacting that array while an application still holds a slice would corrupt a body. The first prototype should use bounded copies for queued chunks, or explicit leases released after consumption/callback acknowledgement. A copy is simpler to review; measure it before adding pooling.

`Http1BodyStream` must stop advancing the parser from the application thread. Instead, it consumes a bounded body queue populated by the protocol owner. Its blocking wait remains part of the application API. Async listeners consume that same ownership mechanism without scheduling a blocking read task.

Preserve request body limits, fixed/chunked lengths, trailers, unread-body discard, close-delimited behavior where applicable, and `Expect: 100-continue`. Body discard must progress incrementally and remain bounded by existing timeout/size behavior. An early response must not wait forever draining a body the client is withholding pending an informational response. Pause parsing at exchange boundaries so a coalesced pipelined request does not change handler order or accumulate unlimited requests. HTTP-to-WebSocket takeover must hand off the exact unread byte range and its ownership.

### HTTP/2

Most individual frame parsers already consume `ByteBuffer`s and can be reused once complete bounded input is available. Replace the blocking outer driver with saved state for the 9-byte frame header and its payload. Maintain a separate pending header-block state across HEADERS and CONTINUATION frames. A partial frame or partial continuation sequence must yield to the event loop.

`FieldBlockDecoder.decodeFrom()` expects a complete HPACK block and treats underflow as a compression error. It need not become incremental in the first migration: the current implementation already collects a bounded compressed field block. Preserve that aggregation bound and call the decoder only after END_HEADERS. Preserve HPACK updates when rejecting a stream so later streams still decode correctly, including during graceful shutdown.

Keep DATA credit accounting separate from outbound writer availability. A stalled response stream must not prevent processing inbound WINDOW_UPDATE, RST_STREAM, SETTINGS, PING or other streams. Stop reading a connection only for a justified connection-level capacity constraint; stopping for one application's stalled stream can deadlock multiplexed progress.

### WebSockets and higher level bodies

WebSocket parsing needs persistent state for partial base headers, extended lengths, masking keys and payloads. Preserve mask offset and UTF-8 validator state across network reads and message fragments. Keep current frame/message limits and event ordering. Waiting for application receive acknowledgement becomes a saved pause plus continuation, rather than `completion.get()` on a reader worker. Read interest must reflect receive demand without losing close, timeout and shutdown behavior.

Multipart/form and JAX-RS stream readers can initially remain unchanged behind the new blocking body adapter. Their filesystem and object allocation behavior still needs separate bounds. A full asynchronous multipart/serialization rewrite is optional follow-up work, not a prerequisite for removing idle socket workers.

## Output and backpressure

Nonblocking `SocketChannel` writes can consume only part of a buffer, including zero bytes. Retain output position and resume on write readiness. Enable write interest only while output needs it; a permanently writable registration can spin. The [Java 11 SocketChannel API](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/nio/channels/SocketChannel.html) establishes these partial-progress semantics.

For HTTP/1, keep one serialization order for informational responses, final headers, chunks, body data and final framing. For HTTP/2, retain the coordinator's per-stream ordering, credit eligibility and connection-control progression. Encoded frames and batches must retain their buffers until transport completion, replacing assumptions in `Http2WriteBatch` that a successful `OutputStream.write()` has consumed the whole region.

A blocking `OutputStream` adapter can submit bounded output and wait for the appropriate completion from an application worker. It cannot lend a caller-owned byte array past the return of `write()` without copying. Async buffers follow the existing `AsyncHandle` contract: ownership returns only when the future finishes or the callback runs. Cancellation currently waits for an active writer to relinquish the buffer; preserve this guarantee even when output is partly encrypted or transmitted. Wire completion means transport acceptance, not proof that the peer application received the bytes.

Queue admission needs a byte policy, not just task counts. Current async output, WebSocket sends and H2 coordinator commands have uncapped pending queues. Specify per-response/stream, per-connection and server-wide retained-byte budgets, with room for necessary protocol control output. Blocking producers may wait for capacity off the event loop. Async producers need demand/completion or explicit rejection; their API path must not secretly block on a full queue. Treat changing rejection behavior as an API decision.

Custom `ContentEncoder.wrapStream()` implementations are an important constraint. Existing gzip, writers and encoders can remain on workers, but a nominally async send that drives a blocking encoder may still occupy an internal worker. A direct async transport path must identify how transformed output is produced and queued; merely substituting a queued `OutputStream` underneath the existing async drain does not remove that wait. Evaluate uncompressed async output first, then compression and custom encoders as an explicit compatibility milestone.

## TLS and encryption

The cryptographic algorithms, certificates, trust configuration and `SSLContext` can continue to come from JSSE. The integration changes from blocking `SSLSocket` to `SSLEngine` over a channel. Existing `SniKeyManager.chooseEngineServerAlias()` already obtains SNI from an engine handshake session; reuse and test it. `BaseHttpConnection` currently gets protocol, cipher and requested server names by inspecting `SSLSocket`, so move those queries behind transport metadata.

The transport needs explicit ownership of encrypted input/output and decrypted input, plus handshake progression, partial output and close state. Use the session's [packet and application buffer requirements](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLSession.html) when sizing buffers; delegated tasks follow the [SSLEngine API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLEngine.html).

The TLS design must cover:

- Progress driven by [handshake states](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngineResult.HandshakeStatus.html) NEED_WRAP, NEED_UNWRAP and NEED_TASK, including subsequent protocol activity after the initial handshake. Resume buffered engine work without assuming a fresh socket-read event is always necessary.
- [Underflow, overflow and CLOSED outcomes](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLEngineResult.Status.html), plus zero progress. Grow buffers using session requirements within an explicit resource policy; do not busy-loop.
- Ordered ciphertext output and completion mapping from application writes to the encrypted bytes awaiting channel drain. Engine consumption alone must not accidentally publish a stronger write-success claim.
- A separately scheduled, bounded delegated-task domain for slow key/trust work. Delegate completion returns to the connection owner; task rejection, timeout and late completion after close have defined outcomes. Exhausted application carriers must not prevent essential TLS progression.
- Client authentication NONE/OPTIONAL/MANDATORY, supplied SSL contexts, provider behavior, cipher/protocol configuration, SNI, ALPN and session reuse. Preserve HTTPS configuration replacement for subsequently accepted connections.
- Graceful close-notify with a deadline and immediate forced channel close. Current forced shutdown deliberately bypasses TLS wrapper close because that can wait behind a stalled write. The NIO implementation must preserve prompt abort.

PROXY parsing remains before TLS. An incremental PROXY decoder may read a buffer containing both the preamble and following TLS bytes; it must transfer the unconsumed range to TLS exactly once. Preserve v1/v2 limits and the existing preamble deadline from acceptance, including queued setup time. Cleartext H2 preface detection similarly needs bounded lookahead and correct handoff.

## Lifecycle and execution rules

A shared loop should own channel readiness, protocol input state, TLS progression and output order for each connection. Application/body-consumption and timer domains submit short commands to that owner. Each connection needs a fairness quota for bytes, frames or commands processed per turn so one busy peer cannot monopolize a loop.

Never run handlers, user callbacks, file IO, body waits, future waits, or blocking encoder writes on the loop. Avoid future completion under transport locks that can execute arbitrary dependent code inline. Preserve callback serialization, application task context and supplied-executor rejection behavior. Essential IO outcomes must not require an application callback to run before transport releases buffers or credits.

Replace stack-based `finally` cleanup with explicit idempotent transitions for accepted/setup, established, draining and closed connections. Closing must fail or finish pending operations, wake body/output waiters, retire admission, release buffers and deliver allowed notifications exactly once. Request cancellation and application completion remain distinct for H2 stream accounting.

`Socket.setSoTimeout()` no longer drives reads. Carry existing preamble, request/body idle, connection idle, SETTINGS acknowledgement, WebSocket and graceful-stop deadlines into the owner/timer model. Preserve the difference between elapsed idle time and an absolute operation deadline. Queue saturation and paused reads must not disable timeout or forced shutdown. Preserve plaintext byte/statistics accounting currently performed by `HttpConnectionInputStream` and `HttpConnectionOutputStream`; ciphertext socket counts can be additional metrics with different names.

`ExecutionResources.shutdown()` currently drains internal work before shutting down the owned application executor so final callbacks can be dispatched. Keep corresponding event-loop/delegated-task/application shutdown ordering without extending the caller's stop deadline. A caller-owned application executor remains caller-owned.

## Architecture choices

| Choice | Benefit | Cost and limitation |
| --- | --- | --- |
| Retain blocking transport, tune policy and admission | Small change; existing parsers and TLS lifecycle stay intact. | Platform connection workers still scale with connections. Limits constrain the cost but do not remove it. |
| Readiness only for idle connections, then blocking workers for active work | Can reduce plaintext idle workers as an intermediate experiment. | Fragmented input and long-lived SSE/WebSockets still occupy active workers; TLS readiness refers to ciphertext. Not a complete HTTPS scaling design. |
| Custom Java NIO transport and JSSE engine | Java 11 compatible; explicit ownership; preserves Mu protocol implementation without a runtime transport dependency. | Mu owns selector mechanics, TLS driver, queues, buffer lifecycle and their security/correctness maintenance. |
| Netty transport and TLS with Mu decoders | Reuses event loops, channel writes and an established TLS driver while retaining Mu HTTP parsing and handlers. | Runtime dependency and reference-counted buffer integration; still requires decoder extraction, blocking bridges and Mu admission/backpressure policy. |
| Asynchronous channels with JSSE engine | Completion-based socket operations are another viable transport basis. | Still requires protocol state, partial operation ownership, TLS and bounded queues; completion threads must obey the same execution rules. |
| Fully asynchronous application APIs | Can reduce workers occupied by streaming/application waits where libraries support it. | Large API and provider migration. Not needed to obtain most idle-connection gains. |

The sibling Mu3 checkout uses Netty `NioEventLoopGroup` and TLS handlers and can supply a comparison baseline. A narrow Netty layer need not mean reverting to Mu3's entire HTTP stack. Its [SslHandler API](https://netty.io/4.1/api/io/netty/handler/ssl/SslHandler.html) supports a supplied engine, handshake completion and delegated-task executors; configure task offloading explicitly rather than relying on its default execution context.

Evaluate custom NIO and the narrow library option against the same transport contract before committing to either. Keep a single decoder/protocol implementation where possible. Maintaining independent blocking and nonblocking protocol stacks would double much of the conformance and lifecycle burden.

## Proposed investigation milestones

1. **Record resource and progress baselines.** Prioritize idle keepalive, SSE and WebSockets; include slow H1 consumers, incomplete uploads and zero-window H2 streams. Compare Java 11/17 platform mode, Java 21 AUTO and PLATFORM, and Java 25 AUTO, with explicit connection/request limits. Reuse the existing conformance harness and saved pinning controls. Record threads, NMT committed/reserved memory, RSS, heap and queued bytes at increasing connection counts and after close. Do not extrapolate the 256-connection snapshots into a capacity guarantee.
2. **Specify the transport contract.** Define byte ownership, write/flush/future/cancellation completion, admission, metadata, EOF and deadlines. Inventory every socket-specific caller before selecting a library. This is the reviewable design boundary.
3. **Extract the HTTP/1 decoder using its blocking driver.** Verify grammar equivalence, then replay every split point for small messages, one-byte feeds, random fragmentation and coalesced messages. No default transport switch is needed for this step.
4. **Build a disposable plaintext H1 transport comparison.** Implement a bounded body bridge and ordered partial writes, with handlers off the loop. Use an isolated checkout/prototype and compare a simple custom loop with a narrow library adapter. Gate on idle-thread slope, memory slope, slow-peer isolation, cancellation and stop deadlines before widening scope.
5. **Prove HTTPS early.** Add the TLS driver/library path, SNI, ALPN, client authentication, delegated-task saturation and fragmented/coalesced PROXY plus ClientHello. A plaintext success alone should not select the final architecture.
6. **Adapt H2 and WebSockets.** Keep H2 flow-control/coordinator semantics and WebSocket event acknowledgement. Include async body/output, SSE and compression/custom encoders in the thread/resource audit. Remove remaining internal waits incrementally.
7. **Run compatibility and sustained-load gates before rollout.** Use the repository's Java 11/17/21/25 checks, Error Prone/NullAway, parser tests, independent h2spec/Autobahn campaigns and real proxy checks. Compare long soaks and offered-load latency against both current Mu4 and Mu3, with complete body verification and workload identity. Select defaults and fallback support only after those results.

Critical deterministic cases include one slow peer alongside healthy connections; pinned Java 21 handlers alongside ready sibling responses; H2 receive-credit progress while output is stalled; callback rejection; shutdown during handshake, partial header/frame and partial encrypted write; cancellation before buffer reuse; upload discard with Expect; and HTTP-to-WebSocket takeover with bytes in the same read. Existing carrier, execution-domain, async-output, admission and shutdown tests supply useful starting cases, but do not establish NIO correctness.

## Decisions to settle before implementation

The first prototype should optimize idle and long-lived transport cost while preserving blocking handlers. It should also demonstrate independent H2 transport progression, without promising general Java 21 application progress under pinning. No throughput improvement should be assumed: the saved hybrid experiment already shows that scheduling changes can materially hurt small-response H2 throughput.

The main choices are whether to own the NIO/TLS driver or use a narrow transport library, whether AUTO should conservatively choose platform application workers before Java 25, and what queue byte limits and async overload behavior are acceptable. Threading policy can be discussed and changed independently; parser/transport migration should not silently decide it. A numerical capacity target and acceptable throughput/latency tradeoff should accompany the first benchmark comparison.

## First decoder extraction

[`Http1MessageDecoder`](src/main/java/io/muserver/Http1MessageDecoder.java) now owns the existing HTTP/1 grammar and consumes a supplied `ByteBuffer` until it produces one event or needs more input. Empty input returns no event until EOF has been signalled; a separate `endOfInput()` transition distinguishes clean EOF, close-delimited body completion and truncation. The decoder preserves its state between feeds and leaves unread bytes in the caller's buffer. It has no stream or socket dependency.

`Http1MessageParser` remains the blocking driver used by existing connections and tests, retaining its 8 KiB input array. Accessible heap buffers keep the existing borrowed-array body path with correct slice offsets. Direct and read-only buffers copy only the body fragment being returned. This extraction does not introduce asynchronous body queues; a future transport must still enforce the documented ownership contract before reusing input storage.

`Http1MessageDecoderTest` exercises every split point in representative fixed/chunked and pipelined requests, informational and HEAD responses, one-byte and random feeds, malformed and truncated messages, explicit EOF, heap/direct/read-only/sliced input, borrowed/copied body ownership, and coalesced WebSocket takeover bytes. Existing parser, field, framing and response-boundary suites continue to exercise the same blocking driver.

Validation on 10 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,723 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. The affected `Http1*` suites passed on Java 11, 17 and 25 with 679 tests each and no failures/errors/skips. The new decoder suite contributes 23 test cases; split-point and randomized-feed loops exercise additional boundaries within those cases. No transport-capacity or throughput claim follows from these correctness checks.

## Review and first plaintext experiment

A review of the extraction found one behavioral regression in the blocking adapter: its exception boundary only covered `IOException` from the source. The old parser also made `HttpException` and `IllegalArgumentException` from a stream terminal. The adapter now preserves that boundary, with a test that supplies each unchecked failure and verifies that subsequent calls cannot read the source again. The grammar, accessible-array offsets, direct/read-only copies, explicit EOF and single-event boundaries remain suitable for the next experiment.

The decoder's WebSocket takeover test establishes only that unread bytes remain in its supplied buffer. The existing live handoff in `Http1Connection.start()` passes only `requestParser.readBuffer` to `WebsocketConnection.runAndBlockUntilDone()`, which wraps it as an empty buffer. Thus it does not transfer the unread range. This predates the extraction. WebSocket clients must wait for and validate the server handshake before sending frames ([RFC 6455 section 4.1](https://www.rfc-editor.org/rfc/rfc6455.html#section-4.1)); the current live handoff test explicitly follows that sequence. A future handoff must carry position/limit and establish who owns the buffer, and needs an integration test in addition to the decoder test.

[`NioHttp1Prototype`](src/test/java/io/muserver/NioHttp1Prototype.java) is now an executable, test-only experiment using `Selector`, `SocketChannel`, the real HTTP/1 decoder, and a small raw-response handler. It has no connection to `MuServerBuilder` or the published server implementation. The purpose is to exercise the ownership and bounded-buffer contract before selecting a transport implementation.

### Contract exercised by the experiment

| Concern | Implemented rule |
| --- | --- |
| Input ownership | The selector thread alone advances the decoder. Each connection owns an 8 KiB network buffer. Body events are copied into a fixed 8 KiB ring before that network buffer can be reused. Application reads copy out of the ring. |
| Receive demand | Decoder input is limited to free body capacity. Full rings disable read interest. Consumption schedules a coalesced continuation and wakes the selector; already-buffered bytes resume without requiring a new socket event. |
| Sequential exchanges | At most one handler runs per connection. Pipelined bytes remain in the bounded network buffer until handler completion and output drain permit the next exchange. |
| Output ownership | A fixed 8 KiB output ring owns copies of application bytes. `write()` waits for space as necessary and returns when all supplied bytes have been copied; `flush()` waits for the ring to drain to the channel. Channel writes advance only by their actual return value, including zero. |
| Completion | Copy admission and channel drain are distinct. Neither proves peer receipt. This prototype's buffered `write()` boundary is not a proposed change to Mu's successful-write accounting or async completion contracts. Production integration needs per-write completion records and accounting at the correct boundary. |
| Scheduling | One platform selector thread runs no handlers or user callbacks. A fixed platform worker pool runs blocking handlers. Parser events, accepts and queued commands have per-turn quotas; each socket write attempts at most 8 KiB. Pipe locks cover bounded copies and nonblocking channel writes, never application calls or capacity waits. |
| Admission | Connection count, worker count and queued handler count are explicitly capped. Worker notifications coalesce to one queued command per connection. Production request admission and server-wide retained-byte accounting are still separate work. |
| EOF and abort | Input EOF is separate from an empty read. A complete half-closed upload can still receive a response. Truncation fails its body reader. Closing fails the rings and wakes body, output-capacity and flush waiters. Channel drain retains the ring lock, so abort cannot release its storage while a write is using it. |
| Early response | If the handler finishes before the body boundary is decoded, the prototype closes after draining its response. It does not wait indefinitely for the rest of the upload. Production unread-body draining and Expect behavior still require their own implementation. |
| Deadlines | A monotonic network-progress idle deadline remains active while reads or writes are stalled. Stop closes connections, then interrupts and awaits workers within bounded joins. Arbitrary handlers that ignore interruption can still outlive shutdown; the harness reports that failure. |

These rules retain 16 KiB of fixed input/output array storage per idle connection and another 8 KiB for an active body bridge, excluding parser token storage, objects, application allocations and kernel buffers. Removing native readers therefore has a memory tradeoff even before TLS. Those array sizes are experiment parameters, not proposed defaults or measured total memory usage.

### Evidence and limits

[`NioHttp1PrototypeTest`](src/test/java/io/muserver/NioHttp1PrototypeTest.java) verifies 128 simultaneous keepalive connections, sends and checks two responses on each, and observes one selector thread plus two created application workers. It checks connection retirement after clients close. This is a count of the prototype's own threads, not a process-wide native-thread measurement or a throughput comparison.

Other tests verify 100,000-byte fixed and chunked bodies followed by pipelined requests; a full body ring while another client gets a response; a real non-reading response consumer that causes zero-progress writes while another client completes; shutdown waking its blocked writer; complete half-close and truncated input; early response without the remaining upload; connection admission and a partial-header deadline. Ring tests force zero/partial writes, wraparound and caller-buffer reuse, and verify that abort wakes readers, producers and flush waiters. Ring high-water checks enforce the 8 KiB capacities.

The experiment omits TLS/PROXY, H2, WebSockets, Mu's handler/request/response API, structured error responses, request-body size admission, request trailers exposed to applications, async callbacks, cancellation futures, compression, file serving and production timeout/statistics integration. Unsupported Expect and upgrade requests fail closed. It uses two platform workers, so it supplies no evidence about application progress under Java 21 carrier pinning. A stalled handler still occupies an application worker; enough stalled handlers exhaust that pool even though the selector remains available.

The socket-specific integration boundary is now concrete: `ConnectionAcceptor` owns accepted-socket admission, PROXY, TLS configuration, protocol selection and stream lifetime; `BaseHttpConnection` obtains local/remote addresses, TLS protocol/cipher and SNI from sockets; `Http1Connection` switches read timeouts and closes or shuts down sockets; `Http2Connection` closes sockets on retirement; the connection stream wrappers publish plaintext byte counts and transport failures. A production transport interface must supply those metadata, deadline, close and accounting operations without pretending a `SocketChannel` can be substituted under the existing blocking lifetime.

The next comparison should place a narrow transport-library adapter behind the same byte-ownership and completion rules, then prove HTTPS early on both viable candidates. Choose an implementation only after TLS progression, delegated tasks, slow-peer isolation, retained memory and completion semantics are measured. H2's frame/payload/continuation driver and WebSocket receive acknowledgement then remain distinct protocol migration steps. The plaintext result alone does not settle those decisions.

Reproduce the focused experiment with Java 11 or later:

```sh
mvn -Dtest=Http1MessageDecoderTest,NioHttp1PrototypeTest test
```

All test listeners bind to `127.0.0.1` on ephemeral ports.

Validation on 10 October 2026: full Java 21 `mvn -Pnullaway clean verify` passed with 4,733 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. After isolating the cancellation test's workers from the common pool, the final `Http1*` plus `NioHttp1PrototypeTest` suites passed on Java 11, 17, 21 and 25 with 689 tests each and no failures/errors/skips; the Java 21 run also enabled NullAway. The prototype contributes nine tests. These results establish the stated correctness cases, not total memory savings or a performance advantage over the existing server.

## Production connection transport boundary

`BaseHttpConnection`, `Http1Connection` and `Http2Connection` now receive a [`ConnectionTransport`](src/main/java/io/muserver/ConnectionTransport.java). They obtain addresses, TLS metadata, read-timeout controls, input shutdown, normal close and forced abort through this interface. They no longer own or inspect a `Socket` or `SSLSocket`. The public handler and connection APIs retain their existing signatures.

[`SocketConnectionTransport`](src/main/java/io/muserver/SocketConnectionTransport.java) is the first production adapter. The acceptor constructs it after protocol setup and supplies its plaintext streams through the existing accounting wrappers. The provided pushback stream survives cleartext protocol detection. Admission, PROXY parsing, TLS handshaking, protocol selection and plaintext byte accounting retain their existing owners. TLS getters query the current established session, preserving visibility of session changes and the existing handling of initial client certificates. Providers without SNI inspection return absent metadata; JSSE explicitly permits [unsupported SNI inspection](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/ExtendedSSLSession.html#getRequestedServerNames()).

The control contract distinguishes normal close from abort. Normal close may send TLS close-notify. Abort closes the underlying accepted socket independently, so it can interrupt pending IO even while another thread is waiting in the TLS wrapper's close. The adapter introduces no shared close lock. HTTP/1 request and WebSocket read timeouts use the same phase transitions through the interface.

Tests exercise a complete HTTP/1 request body, real Mu handler, response and cleanup with a transport implementation that owns no socket. Adapter tests cover prefetched input, read timeout, half-close with usable output, abort concurrent with a blocked wrapper close, current-session metadata, optional/provider-specific SNI metadata, and real TLS 1.2/1.3 SNI and client-certificate queries after connection closure. Existing H2 writer fixtures now share the transport object; tests that inject socket faults reach into the socket adapter explicitly.

This interface covers connection controls and metadata. The next production migration still needs a resumable byte path with bounded body delivery and transport-aware write completion, followed by the channel/TLS implementation. Keeping stream access on the blocking adapter, outside the shared interface, allows that byte path to be driven by readiness without making the new transport implement blocking socket reads.

Validation on 10 October 2026: full Java 21 `mvn -Pnullaway clean verify` passed with 4,741 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. The affected transport, connection metadata, HTTPS/client-certificate, PROXY, HTTP/1 termination and H2 upload/write-completion suites passed on Java 11, 17 and 25 with 157 tests each and no failures/errors/skips. The transport boundary adds eight test cases. This step establishes compatibility and a socket-independent control boundary; it does not change production thread usage or establish nonblocking TLS behavior.

## Bounded transport streams

[`TransportInputBuffer`](src/main/java/io/muserver/TransportInputBuffer.java) and [`TransportOutputBuffer`](src/main/java/io/muserver/TransportOutputBuffer.java) provide fixed-capacity plaintext bridges between transport progression and the existing blocking stream consumers. They are internal production components exercised through the real `Http1Connection`, handlers, request bodies, response framing and accounting wrappers. The socket acceptor still supplies its existing streams; selecting a channel backend remains a separate step.

Input offers copy only as many bytes as fit and advance the caller's `ByteBuffer` by that amount. A full buffer returns zero immediately, leaving the remainder with the transport. Consuming a full buffer schedules a capacity notification outside its lock. EOF allows buffered bytes to drain; failure discards them and wakes readers. Read timeouts apply to each subsequent read, and interrupted waits preserve the interrupt flag. A transport must stop reads at zero capacity and coalesce wakeups into its own work queue.

Output writes copy into fixed storage and wait until the sink has consumed the entire write. This is the completion boundary used by the existing successful-write counters and async write futures. The transport performs one bounded sink call per drain, retaining any suffix after a zero or partial write. Whole writes are serialized with a `ReentrantLock`; capacity and completion waits use conditions. Abort bypasses the writer lock, fails waiting writers/flushes/closes, and cannot release storage while the sink is using it. Sink failures and interrupted admitted writes make the stream terminal because a prefix may already have been transmitted. Internal notifications only schedule transport work; neither bridge dispatches application callbacks.

`Http1BodyStream` now obtains trailers through `Http1MessageReader`, removing its concrete dependency on the blocking parser for that operation. Reads and unread-body discard retain the same trailer publication boundary.

[`Http1BufferedTransportTest`](src/test/java/io/muserver/Http1BufferedTransportTest.java) drives real HTTP/1 exchanges with a 31-byte input buffer, a 17-byte output buffer and sink writes of at most three bytes. It verifies complete fixed/chunked uploads, trailers, pipelining, unread-body discard, half-close, truncation, size rejection, forced shutdown and async cancellation. It verifies that queued response bytes do not advance successful-byte accounting, async write futures or response completion before drain. The stream suites additionally cover zero writes, buffer wraparound, caller-storage reuse, ordered concurrent writes, terminal failures, input timeout and interruption. These small capacities force fragmentation and capacity waits; they are test parameters, not proposed defaults.

The bridges do not make the existing connection loop resumable: it still occupies a worker while waiting for input or handler completion. The next driver must schedule idle/header processing and exchange continuations without assigning a waiting worker to every connection. Input also retains the parser's existing 8 KiB array in addition to the bridge capacity. These bounds do not include application allocations or Mu's existing asynchronous write/callback queues. A future input driver must define receive accounting at transport admission rather than accidentally double-counting bytes through both paths.

The output sink contract currently fits plaintext channel writes. Merely consuming plaintext with `SSLEngine.wrap()` cannot complete a write under this contract: the corresponding encrypted output must also progress. TLS therefore still needs explicit encrypted-buffer ownership and completion tracking, as well as handshake/delegated-task progression. No default transport, TLS implementation, or thread policy changes in this step.

Validation on 10 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,762 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. The final `Http1*`, `TransportInputBufferTest` and `TransportOutputBufferTest` suites passed on Java 11, 17 and 25 with 702 tests each and no failures/errors/skips. This step adds 21 test cases. The integration fixture drives transport readiness manually; these results establish the stated ownership and compatibility cases, not selector fairness, reduced thread usage or nonblocking TLS correctness.

## TLS record driver

[`TlsEngineDriver`](src/main/java/io/muserver/TlsEngineDriver.java) is an internal production component for a channel transport. It owns the `SSLEngine` and three buffers: encrypted input, encrypted output and decoded plaintext. Each starts at the provider's session size and can grow to a fixed per-buffer limit of 256 KiB. This bounds the driver's arrays, not provider-internal handshake state, certificate chains, queued delegated tasks or total connection memory. The current socket acceptor still uses `SSLSocket`.

The implementation proceeds with JDK channels and `SSLEngine`, keeping the driver small and independent of the HTTP protocols. A transport library would not remove the work needed to make the existing protocol lifetimes resumable or preserve Mu's write completion and accounting. No library comparison or throughput advantage has been established; capacity and performance measurements remain acceptance gates for the integrated transport.

### Ownership, progress and completion

All driver calls belong to one transport owner. An advance performs at most one engine operation or delegated-task transition; the future channel loop must limit the number of advances in a turn. Channel reads and writes have explicit byte budgets. Full input or plaintext buffers apply backpressure, and zero-progress engine results stop repeated work until new input or a state transition makes progress possible. Buffered work resumes without requiring another network event.

Delegated tasks are collected into a bounded batch and submitted to a separate executor. While that batch runs, the driver performs no engine calls. Completion schedules transport work; it never invokes handlers. Rejection and task failure make the connection fail. Abort releases the driver's buffers without waiting for the task or accessing its engine. The integrated listener must additionally bound the shared task executor, enforce handshake deadlines including queued tasks, and safely handle task completion after retirement.

Plaintext output uses a duplicate of the caller's buffer. A successful wrap leaves the caller's position unchanged until all associated ciphertext has drained to the channel. Later calls acknowledge the retained prefix, including when a later transport turn has a smaller budget. The caller must keep that prefix unchanged until acknowledgement. This supports `TransportOutputBuffer` without treating plaintext consumption by the engine as a successful socket write. Channel drain establishes neither peer receipt nor application consumption.

The driver continues inbound work while encrypted output is stalled. It handles handshake records, session tickets, post-handshake tasks, TLS 1.2 renegotiation and TLS 1.3 key updates through the same progression. Application plaintext is never published before initial handshake authentication completes. Negotiated session metadata and ALPN remain accessible to the owner; the eventual transport adapter must publish metadata safely to handler threads.

### EOF and failure

TCP EOF is recorded separately from an empty read. Buffered records and plaintext drain before `closeInbound()` checks for a missing close-notify. Truncated records and missing close-notify fail rather than appearing as clean TLS EOF. A TLS 1.3 peer's close-notify closes input while allowing the response direction to finish.

Orderly output close waits for wrapped application bytes to be acknowledged, requests engine closure, and drains the resulting records. Failure is sticky for application operations. A separate, optional failure-close path preserves any partly sent record before trying to send the TLS alert. It cannot turn a failed application write into success. The caller must impose a close deadline; hard abort and channel IO failure discard pending output immediately. TLS close must never prevent transport abort or server shutdown. These rules follow the JDK's [SSLEngine lifecycle and transport contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLEngine.html).

### Evidence and remaining integration work

Real paired JSSE engines test TLS 1.2 and 1.3 with one-byte fragmentation, duplex payloads across record boundaries, mutual authentication, SNI, ALPN, plaintext backpressure, output stalls, missing client certificates, corrupted records, truncated EOF and close-notify. Scripted engine tests force buffer overflow/underflow and size caps, zero progress, task rejection/failure, pending-task abort, post-handshake control output, partial encrypted writes and failure alerts. A real `SSLSocket` client also exchanges data and closes against a readiness-driven `SocketChannel` server, with and without HTTP/2 ALPN.

Java 25 testing exposed an existing `SniKeyManager` null dereference during TLS 1.2 session-ticket restoration: a certificate can be requested before the new handshake session exists. The engine path now consults its established session when necessary and falls back to the configured default certificate when SNI is unavailable. The socket path also tolerates absent handshake metadata without calling `getSession()`, which could initiate blocking IO. Reused-context tests with two different certificates check that the requested SNI certificate survives new connections, session reuse and renegotiation; they also force a full TLS 1.2 renegotiation.

Validation on 10 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,800 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. The TLS, existing HTTPS and client-authentication suites passed on Java 11, 17, 21 and 25 with 54 tests each, zero failures/errors/skips. The driver adds 38 cases. Listener-level task saturation, handshake/close deadlines, cancellation races, connection admission, PROXY-before-TLS, HTTP/2 and WebSocket over this driver, and sustained slow-peer isolation remain to be implemented and tested. Component correctness does not establish those properties or reduce the current server's connection-thread usage.

## HTTP/1 exchange continuations

`Http1Connection.ReadDriver` can now advance HTTP/1 header parsing from readiness notifications and suspend while an exchange is active, without assigning a waiting worker to the connection. The existing `start()` method remains the blocking adapter used by the socket listener. Both paths share request preparation, admission, handler execution, exception handling, response cleanup, rejection and completion accounting.

The parser retains its 8 KiB input buffer. A polling turn consumes existing buffered bytes and makes at most one nonblocking read from the input bridge, then returns one event or needs-more-input. Empty input and drained EOF are distinct. After headers arrive, the exchange exclusively owns the parser through the existing blocking request-body API. The readiness owner continues feeding the bounded bridge but cannot parse ahead. Cleanup discards any unread body and publishes trailers before returning parser ownership to the next header turn. CompletableFuture completion supplies the cross-thread publication boundary for that handoff.

Request preparation and application-executor submission run on the internal executor: a supplied application executor could execute a task inline in its `execute()` method. Async response completion registers a continuation and releases the worker. Cleanup and rejection responses also run off the readiness owner because discarding an upload or finishing output can wait. A rejected cleanup submission still retires the exchange and releases request admission. The transport is responsible for coalescing continuations and bounding advances per turn.

Receive accounting for this path belongs to the transport when it admits plaintext into the bridge; body readers use the unwrapped input buffer. Output retains the existing successful-write accounting wrapper and its drain boundary. This avoids counting header and body bytes through two separate paths.

Two lifecycle defects were addressed alongside the handoff. Forced HTTP/1 shutdown now completes a suspended async request even when it has no pending IO to fail. WebSocket takeover transfers the parser's actual unread range and buffer ownership rather than treating its array as empty. The HTTP parser rejects further reads after takeover. The readiness driver also rechecks shutdown before activating a WebSocket whose upgrade response has already completed. WebSocket receive progression itself still uses the blocking adapter on an internal worker; it requires a separate migration.

`Http1BufferedTransportTest` now exercises both drivers with a 31-byte input ring, a 17-byte output ring and three-byte sink writes. `Http1ReadDriverTest` runs 128 idle connections and two rounds of suspended async requests with one application worker and one internal worker, checking that both executors remain available while responses are suspended. It also checks queued-request shutdown, handler rejection, cleanup rejection, async shutdown without pending IO, prefetched WebSocket frames and shutdown between upgrade response and takeover. Parser polling tests cover partial headers, explicit EOF, terminal failures, pipelined bytes already in the parser, bounded read work and exclusive upgrade ownership.

These fixtures drive readiness manually. They do not establish production listener thread counts, selector fairness or TLS/HTTP integration. Async body reads and output rendering can still occupy internal workers while waiting on a bridge; those waits must be addressed separately from preserving the blocking handler API. The next step is to integrate channels, protocol selection, PROXY and the TLS driver with listener admission and lifecycle, then replace the remaining H2 and WebSocket blocking progression. Default listener selection and threading policy have not changed.

Validation on 11 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,822 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. The HTTP/1, input-buffer, execution-domain, request-admission, async and WebSocket suites passed on Java 11, 17, 21 and 25 with 891 tests each and one skip before the final upgrade/shutdown guard. The final readiness-driver and parser-polling suites then passed on Java 11, 17 and 25 with 11 tests each and no skips; the full Java 21 run includes the guard and its regression test. This step adds 22 test cases.

## Incremental PROXY preamble parsing

[`ProxyProtocolDecoder`](src/main/java/io/muserver/ProxyProtocolDecoder.java) consumes a supplied `ByteBuffer` through one v1 or v2 preamble and leaves following TLS/HTTP bytes untouched. It returns no metadata until the entire preamble has been validated, including all v2 TLV lengths. Empty feeds preserve state; EOF and malformed input are terminal. The existing blocking parser and the decoder share v1 address validation and v2 fixed-header/address decoding.

The decoder retains a 107-byte scratch array and at most a 216-byte UNIX address block. It skips TLV values and LOCAL payloads by advancing the supplied buffer, without retaining or allocating their declared payload size. V1's 107-byte limit includes CRLF. V2 payload caps, supported versions, address-family/transport combinations and minimum address lengths are checked before waiting for payload. LOCAL still ignores its family and payload interpretation, while canonical UNSPEC still validates TLV structure. The listener remains responsible for an absolute deadline from acceptance, including queued setup time, and for limiting bytes processed per readiness turn.

Validation on 11 October 2026: the incremental decoder, existing PROXY listener/parser and configuration suites passed on Java 11, 17, 21 and 25 with 82 tests each, zero failures/errors/skips; Java 21 also enabled Error Prone/NullAway. The ten new cases cover every split and truncation point in representative IPv4, IPv6, UNIX, LOCAL and UNSPEC preambles; heap/direct/read-only/sliced buffers; single-byte feeds with reused storage; maximum-size payloads and repeated empty TLVs; invalid fixed headers and TLVs; version/cap enforcement; v1 limits; and preservation of coalesced TLS bytes. Listener setup is not yet using the incremental decoder.
