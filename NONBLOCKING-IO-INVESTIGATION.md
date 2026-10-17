# Mu4 nonblocking IO investigation

Implementation status on 14 October 2026: the channel transport migration and its scoped validation are complete on this branch. HTTP/1, HTTP/2, WebSocket and TLS use channel transport by default. Public blocking handler APIs remain available; asynchronous transport waits use resumable protocol and response work. `AUTO` selects platform task workers before Java 25 and virtual task workers from Java 25. The [final source replay](#final-source-replay-14-october-2026) records the completed build, runtime and protocol checks; the [final bulk comparison](#final-bulk-comparison-14-october-2026) records the improvement and remaining throughput tradeoff. Earlier dated sections are implementation checkpoints, and the initial assessment below describes the original source baseline.

Mu4 can reduce the number of waiting transport workers without replacing its blocking handler APIs. The most useful architecture to evaluate is nonblocking socket and TLS transport, resumable protocol processing, and application workers that retain the current request and response streams. The biggest benefits should be idle connections, SSE, WebSockets, slow uploads, and slow response consumers. These are expected benefits from the ownership model, not measured NIO performance results.

The difficult parts are buffer ownership, backpressure, partial output, TLS progression, and connection lifecycle. HTTP/1 already has a stateful parser worth retaining. HTTP/2 already has a serialized output coordinator worth retaining. At the source baseline, neither connection implementation could run on a shared event loop because both performed blocking reads and waits.

Source baseline: `50ad1a7072fffe959770531204a09b2ddd3b4637`, inspected on 10 October 2026. Investigation branch: `investigate/nonblocking-io`. The ownership map and migration analysis below describe that baseline. The hybrid-transport measurements come from the saved 5 October experiment at the same source baseline. Later dated sections record the decoder extraction, production transport and protocol migration, response ownership, and validation. The original production connections used blocking socket IO and the earlier thread policy.

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

## Channel listener integration (internal switch)

`MuServerBuilder.useChannelTransport` now selects a real channel listener for internal integration tests. It remains false by default. `ConnectionAcceptor` retains its fixed acceptor thread and shared connection admission, then hands accepted channels to one `ChannelTransportLoop` per listener. The loop owns channel readiness, PROXY decoding, cleartext protocol detection and `TlsEngineDriver` progression. HTTP/1 uses its resumable read driver; H2 and WebSocket still use their existing protocol workers over the bounded bridges. This is an intermediate backend, not the completed migration.

Each connection owns 8 KiB input and output rings and an 8 KiB incoming buffer, in addition to protocol and TLS storage. Coalesced PROXY/TLS input transfers to a separate temporary ciphertext prefix so decoding plaintext never depends on consuming that entire prefix first. Terminal bridge failure releases the ring arrays under their storage locks. TLS metadata is published once per completed handshake generation, retaining the initially authenticated client certificate. Plaintext input counts on bridge admission; blocking body readers retain EOF/failure observation without counting bytes again. Output still counts only successfully drained application writes. Actual channel progress updates connection idle time independently of whole-write completion.

Read/write attempts are capped at 8 KiB and each connection drive has sixteen advances. A ready batch contains at most 128 entries and self-rescheduled work waits for another selection turn. Empty/full input, partial writes, pending delegated tasks and stalled TLS output disable work until readiness, capacity or completion changes. The listener's delegated TLS executor uses two to eight platform workers, according to available processors, with a 256-entry queue. Rejection fails the handshake and counts overload; it never runs the task on the readiness owner. These are internal migration settings, not new public tuning APIs.

PROXY retains its configured absolute preamble deadline from acceptance. TLS/cleartext protocol selection has a ten-second deadline, restarted after a successful PROXY preamble; TLS orderly/error closure has a separate one-second bound while the socket remains open. Neither a blocked provider task nor a slow peer can extend forced shutdown. A closed socket can still have retained application work: retirement, active-request accounting and admission wait for the protocol completion boundary. In particular, H2's already-delivered responses retain their existing application-completion and idle-timeout semantics. Failure of the selector closes the listener, cancels its timers and hands final completion to outstanding protocol futures. Once physical closure has been observed, a retained handler is resumed only by a real completion or deadline event; it cannot continuously reschedule the readiness loop during forced shutdown.

Resumable connections explicitly retain the execution resources until protocol retirement. A suspended async exchange may have no internal worker running, so internal-executor termination alone no longer proves that all cleanup and completion callbacks have been submitted. Shutdown stops the timer immediately, then closes the internal and owned application executors after the last transport releases its registration. Registrations cannot be acquired after shutdown starts; releasing them is idempotent. This preserves callback delivery while keeping forced stop bounded.

Integration also exposed two error-path gaps. HTTP/2 now handles all input `IOException`s through its peer-input-failure path, including failures from a TLS/plaintext bridge, so streams are cancelled and retained applications remain accounted for. WebSocket input failures now report `ClientDisconnectedException` with the original socket/TLS failure as its cause; timeouts and interruption remain distinct. This prevents default error handlers from trying to send a close frame on a failed transport before completing their error notification. TLS truncation remains a failure, not clean TLS EOF.

`ChannelTransportTest` exercises real HTTP/HTTPS listeners, HTTP/1 pipelining and half-close, TLS 1.2/1.3 large transfers and SNI, ALPN and cleartext H2, coalesced v1/v2 PROXY plus ClientHello, WebSocket upgrade/disconnect, slow readers, async cancellation, setup timeout and admission recovery, TLS task rejection, blocked trust tasks during shutdown, H2 input failure, and unexpected selector failure. Its 128-connection HTTP/1 fixture uses one application worker and one internal worker, checking that both remain available while connections are idle or async requests are suspended. Existing HTTPS/client-authentication, PROXY, WebSocket and RFC 9113 tests have also been run through the channel backend, as have H2 peer-close, cancellation and body-timeout suites.

The remaining protocol work is explicit: replace the H2 handshake/frame/CONTINUATION reader, remove stalled-socket waits from its output adapter, make WebSocket receive and callback progression resumable, and remove framework waits from HTTP/1 async body/output operations. Blocking handler APIs still need their worker adapters. Default backend/thread policy changes, conformance tools and workload comparisons remain later gates.

Validation on 11 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,860 tests, zero failures/errors and nine skips, including Error Prone/NullAway, Javadocs, packaging and dependency analysis. With channel listeners selected for the existing protocol suites, the combined channel/TLS/HTTP/1/HTTP/2/WebSocket/PROXY/executor-lifecycle suite passed on Java 11, 17, 21 and 25 with 634 tests each and no failures/errors/skips. The blocking-only executor-rejection fixture explicitly retains the blocking adapter. The 26 new real-channel cases include a CPU-time check that the readiness owner does not spin while forced shutdown retains a still-running handler. The default listener remains the socket adapter.

## HTTP/2 field-block resumption

`Http2HeaderBlockDecoder` assembles HEADERS and CONTINUATION payloads without reading a transport. The existing blocking reader now adapts to this decoder. A complete initial frame is decoded directly from borrowed storage; fragmented blocks copy only their compressed field bytes into bounded owned storage. The physical-frame reader remains responsible for accumulating a complete payload and reporting EOF. Continuation ordering, stream identity, aggregate size and empty-fragment limits are checked before waiting for the next payload.

Recoverable header-size and self-dependency errors are reported after decoding the full HPACK block, preserving the connection's shared dynamic table. Compression failures take precedence. Failed decoders retain their original error and cannot resume; following streams use fresh block decoders with the same HPACK state. Padding and priority bytes are excluded from the compressed block, and following frame bytes remain untouched.

Validation on 11 October 2026: Java 21 with Error Prone/NullAway passed 488 header-fragment, every-byte split, HPACK and RFC 9113 HEADERS tests with no failures/errors/skips. New cases exercise every fragment boundary across heap, direct and read-only slices, overwrite consumed source storage, reuse the resulting dynamic table on a following stream, and check error precedence and terminal behavior. This extraction prepares the connection reader for resumption; by itself it does not release a connection's reader worker.

## HTTP/2 reader continuations

Channel connections now use `Http2Connection.ReadDriver` for the client preface, SETTINGS, physical frame headers/payloads and logical HEADERS/CONTINUATION blocks. Each serialized internal task handles at most 64 physical frames and returns when input is incomplete. It retains the existing bounded physical-frame buffer and per-connection HPACK state. The blocking adapter shares frame dispatch, recoverable header rejection and input-failure logic with the new reader.

The selector only schedules the reader. Frame effects remain off the selector because request/completion dispatch can run application code with a supplied executor. Handler submission itself is separate from the serial read task, allowing a saturated caller-runs executor to run a blocking body reader while another internal task delivers its DATA frames. A handler executor throwing before dispatch now cancels and retires the undispatched exchange and sends an INTERNAL_ERROR reset.

Completion joins reader shutdown, writer termination and retained application work without occupying a waiting worker. EOF and arbitrary plaintext/TLS bridge failures preserve the established treatment of already-delivered responses. Unexpected reader failures cancel streams before retirement. Resource leases keep the owned internal executor alive; an exceptional rejected reader submission uses a separate cleanup task so failure callbacks cannot run on the notifying selector. Terminal input notifications wake suspended readers exactly once per state transition; the reader rechecks readiness after releasing its task-ownership flag to avoid missing concurrent offers.

`Http2ReadDriverTest` checks every transport split through the preface, SETTINGS, initial headers, continuation and a following stream; EOF at every incomplete position; full-size DATA frames through 1-, 7-, 17- and 8192-byte input rings; executor rejection; and 128 idle/partial/suspended connections sharing one internal worker. Real-channel tests repeat the 128-connection check over plaintext and TLS, exercise blocking handlers through a saturated caller-runs executor, and inject plaintext-bridge failure.

Validation on 11 October 2026: Java 21 `mvn -Pnullaway clean verify` passed with 4,886 tests, zero failures/errors and nine skips, including Javadocs, packaging and dependency analysis. The channel HTTP/2 and RFC 9113 regression run passed 940 tests with no failures/errors/skips; the socket-specific upload-retirement fault injector retained its socket path in the full build. The combined channel, resumable-reader, header-decoder, bounded-input, peer-close, cancellation and selected RFC lifecycle suites passed on Java 11, 17, 21 and 25 with 149 tests each and no failures/errors/skips. These runs also include an additional regression for an unexpected handler-executor exception followed by a successful request on the same connection.

The current reader's initial SETTINGS write and the existing response writer can still wait for transport drain. Removing those output waits is the next step. The default backend remains the socket adapter; WebSocket receive/callback resumption, asynchronous body/output adapters, defaults, conformance and workload gates remain outstanding.

## Asynchronous HTTP/2 transport output

`TransportOutputBuffer` now supports an exclusive asynchronous writer in addition to its blocking adapter. It accepts one pending source at a time and progressively copies that source into the existing bounded ring during transport drain. A completion means the entire write reached the sink; the TLS sink still includes ciphertext drain in that acknowledgement. Source storage remains borrowed until completion. Failure releases retained source/ring storage and completes the internal future outside the ring lock. Blocking and asynchronous writers cannot mix on a claimed buffer, and the internal future is not a cancellation API: cancellation fails or aborts the transport.

Channel HTTP/2 connections use this adapter for the initial SETTINGS/ACK and subsequent frames. The reader suspends while initial output is pending, including when the peer pipelines more input. ACK registration precedes further input processing. A malformed preface's GOAWAY is also drained asynchronously before retirement. Forced shutdown releases a stalled handshake without dispatching buffered requests or arming an acknowledgement timer afterward.

The writer serializes each logical candidate into owned bytes off the selector, starts one asynchronous write, and returns its worker while output is stalled. A later task publishes END_STREAM, successful-byte accounting, task completion, SETTINGS acknowledgement timing and GOAWAY grace timing after successful drain. It processes coordinator commands while waiting but preserves the in-flight frame's ownership. Protocol and handler-executor failure cleanup stays off the readiness owner. The socket adapter retains its existing buffered blocking writer and shares lifecycle/completion bookkeeping.

The owned encoding avoids retaining arrays that an OutputStream-based frame encoder can reuse after returning from `write`. It adds a copy and currently submits one logical frame per asynchronous write; batching and allocation costs remain workload gates. The transport ring is bounded, but total retained output is not a universal fixed amount: response header blocks, application payloads and higher-level asynchronous queues keep their existing lifetimes and limits.

Tests cover partial and zero writes with 1-, 7- and 8192-byte rings; direct/read-only source views; exclusive writer ownership; sticky sink/abort failures; callbacks outside buffer locks; abort during sink ownership; stalled initial SETTINGS; pending final responses across peer input close; and delayed GOAWAY. A real-listener fixture holds three H2 responses against unread sockets with one internal worker while a healthy connection completes, over both plaintext and TLS, then checks cancellation and request retirement.

Validation on 11 October 2026: Java 21 `mvn -Pnullaway clean verify` passed 4,905 tests with zero failures/errors and nine skips, including Javadocs, packaging and dependency analysis. The channel H2/RFC 9113 regression run passed 944 tests, and the additional controlled-output/transport checks passed 29 tests. The combined channel, reader, asynchronous-output, input/output ownership, peer-close, cancellation and selected RFC lifecycle suites passed on Java 11, 17, 21 and 25 with 172 tests each and no failures/errors/skips.

This removes waiting workers from the channel H2 connection reader and transport writer, including handshake output. Higher-level asynchronous response/body operations still have worker adapters, and WebSocket receive/send progression still needs migration. Backend/thread-policy defaults, conformance and workload comparisons remain outstanding.

## Incremental WebSocket frame decoding

`WebsocketFrameDecoder` consumes arbitrary ByteBuffer fragments without transport reads or application callbacks. It retains a fixed 14-byte header scratch area and at most one payload bounded by the configured frame limit. The existing blocking receive loop now adapts to this decoder; callback delivery still occurs once per complete frame. The decoded payload owns its storage, so direct, read-only and reused input buffers are safe. This adds a copy for small frames compared with the previous borrowed-slice path; allocation and throughput need workload measurement.

The decoder validates frame headers and limits before allocating payload storage, validates UTF-8 after each unmasked byte, and keeps fragmented-text validation separate from control-frame payloads. Close codes and reasons are checked before publishing a peer close. Invalid input and EOF without a close frame are terminal. A completed frame stops precisely at its boundary, leaving subsequent input for the next call.

This extraction also fixes three reproduced framing defects: independent final messages no longer accumulate against one message-size budget; control payloads no longer count toward a fragmented message's limit; and nonminimal extended lengths are rejected as required by [RFC 6455 section 5.2](https://www.rfc-editor.org/rfc/rfc6455.html#section-5.2). Aggregate length checks use subtraction to avoid long overflow.

Java 21 with Error Prone/NullAway passed all 170 WebSocket tests with zero failures/errors/skips. Decoder cases cover every split for small frames, all extended-header boundaries and representative large-payload splits, one-byte feeds, heap/direct/read-only/sliced buffers, owned payload storage, message boundaries, interleaved control frames, early UTF-8 rejection, close validation and EOF positions. Wire regressions exercise both socket and channel backends. Receive continuations and asynchronous WebSocket output are the next stages; this parser extraction alone does not remove waiting workers.

## WebSocket receive continuations

Channel HTTP/1 upgrades now hand their remaining parser input to `WebsocketConnection.ReadDriver`. A serial internal task advances at most 64 steps before yielding and suspends on partial input or application-event completion. The selector only schedules work. The socket adapter continues to use the shared frame decoder through its blocking receive loop. This removes a waiting worker from idle channel WebSockets and from deferred receive callbacks.

Read deadlines are explicit because `TransportInputBuffer.readAvailable` does not wait or apply an InputStream timeout. A deadline starts when a transport read would wait, resets after positive input, and pauses while a callback owns the current frame. Each deadline has a distinct generation; a canceled callback that was already running cannot expire a later read or an outstanding application callback. Socket timeouts and interrupted reads preserve their exception type, while other transport failures retain their cause inside `ClientDisconnectedException`.

Connection completion waits for the current receive event and queued error/shutdown events. A failed readiness owner explicitly wakes the WebSocket continuation without needing another selector turn. Normal transport termination retains a deferred receive until its acknowledgment arrives. Application-executor rejection needs a different terminal rule: a receive acknowledgment can be chained to the very write callback that was rejected. Rejection therefore retains an application method that is still executing, then cancels its deferred acknowledgment wait when that method returns. Decoded payload storage is independently owned and is never recycled into another frame after retirement. Late acknowledgments cannot resume delivery. Unexpected executor exceptions also terminate queued work instead of stranding the event runner.

Tests drive 128 idle, partial-frame and deferred-receive WebSockets using one internal worker and one application worker, both through a controlled input bridge and through plaintext/TLS listeners. They check retained payloads and connection accounting during shutdown. Controlled timer tests fire obsolete deadlines, suspend and resume callback acknowledgments, and check timeout state and transport abort. Rejection, application failure, error-callback failure and readiness-owner loss have dedicated cases. Existing ping/idle-timeout tests now pass with a single internal worker on the channel backend; the previous blocking WebSocket reader starved those tasks.

A half-close regression also exposed a channel shutdown leak: after the WebSocket reader finished, HTTP/1 had already published CLOSED, while an asynchronous send could remain stuck in the output ring. Its idle-timeout path no longer aborted the transport, and the channel close deadline only started after that ring drained. The deadline now covers remaining plaintext output as well as TLS shutdown. A stalled plaintext peer reproduces the original leak; both plaintext and TLS 1.3 cases now check bounded retirement and failed-send completion.

Asynchronous send adapters and scheduled ping writes still use blocking output. Protocol-error close writes and synchronization with an in-flight server close can also wait. Those are the next migration step, including close-frame/TLS-drain ownership and failure completion. The default backend remains the socket adapter. Higher-level HTTP body/response adapters, defaults, independent conformance and workload comparisons remain outstanding.

Validation on 11 October 2026: before the final close-deadline follow-up, Java 21 `mvn -Pnullaway clean verify` passed 4,936 tests with zero failures/errors and nine skips, including Javadocs, packaging and dependency analysis. The channel receive/callback/upgrade matrix passed 237 tests on each of Java 11, 17, 21 and 25. After the close fix, all 44 channel and controlled-reader checks passed on each of those four JDKs; Java 21 also passed 57 channel callback and slow-peer lifecycle cases. The latter cover HTTP/1, HTTPS, HTTP/2 and WebSockets with both owned and supplied executors. The plaintext half-close case failed before the fix and passed afterward. The slow-peer fixture now sets socket buffer sizes through either transport, and the callback-rejection fixture awaits actual connection retirement instead of assuming a socket-executor barrier also synchronizes the channel selector.

## WebSocket output continuations

Channel upgrades now claim the asynchronous transport writer after the HTTP 101 response drains. `WebsocketFrameWriter` serializes complete frames, submits the header and payload separately, and suspends while either write waits for transport drain. It borrows a duplicate of the caller's payload instead of copying a large message into another frame-sized array. Successful-byte accounting, fragmentation state, close publication and send completion follow the transport acknowledgement, including ciphertext drain for TLS. Public blocking sends wait on the same queue from their calling thread; asynchronous sends do not retain a waiting IO worker.

The socket and channel adapters share `WebsocketWriteState`. Validation precedes output, and state changes follow successful writes. A mismatched text/binary fragment now throws the documented `IllegalStateException`; the old implementation silently ignored it. Invalid commands leave a valid fragmented message intact. Transport failure remains sticky, and later send failures retain its cause. Writes after a successfully sent close retain their closed-state exception.

Automatic pings enter the writer queue without blocking timer or IO workers. Their payload is generated when the command reaches the head, so preceding queued messages do not inflate ping latency. A late queued ping is skipped after closing starts. Default pong, peer-close, error-close and shutdown replies participate in deferred event completion. Protocol-error closes also suspend the reader, and an early peer close waits for the pending local close frame to drain before publishing handshake completion.

Future listeners only publish state or schedule continuation tasks. Application write callbacks are dispatched outside the serial writer's turn: a supplied executor may run a callback inline, and that callback may perform another blocking send. Unexpected callback-executor exceptions terminate queued events rather than stranding their mailbox.

Retirement stops accepting frames, requests physical transport closure, and then waits for borrowed output and error notification to finish. Requesting closure before joining the writer lets the bounded close deadline release stalled output without a circular wait. Interrupting a public blocking send aborts the transport but still waits for ownership of its source buffer to return before throwing and restoring the interrupt flag. Queued writes can fail immediately; an active transport borrow cannot.

Focused tests cover short/extended headers, direct/read-only/sliced buffers, partial and zero writes, invalid fragments and closes, sticky failure, queued automatic pings, crossing closes, protocol-error close suspension, interruption ownership, callback rejection and callback reentrancy. A real-listener test holds three unread 8 MiB sends and queued pings with one internal and one application worker, completes a healthy HTTP request, and then checks cancellation and retirement. It runs over plaintext, TLS 1.2 and TLS 1.3. The late-error case explicitly holds an error callback after physical transport closure and verifies that protocol retirement still waits for it.

Higher-level asynchronous HTTP body/response operations still use blocking stream adapters. Those adapters, transport/thread-policy defaults, independent conformance and workload comparisons remain outstanding; this stage does not change the default listener.

Validation on 11 October 2026: Java 21 `mvn -Pnullaway clean verify` passed 4,977 tests with zero failures/errors and nine skips, including Javadocs, packaging and dependency analysis. The channel WebSocket/upgrade/output matrix passed 277 tests on each of Java 11, 17, 21 and 25 with no failures/errors/skips. The final late-error ownership case was added after that matrix; the full Java 21 build includes it, and all 15 controlled-reader/ownership checks subsequently passed on Java 11, 17 and 25. The initial Java 11 channel run caught missing cause chains on post-disconnect writes; the verified implementation preserves the original IO cause.

## Async body ownership during completion

Completing an asynchronous response previously allowed request cleanup to overtake its body reader. A reproducer sends HTTP/1 headers with `Content-Length: 3`, registers a body listener, waits until the reader is blocked, and completes the response before sending any body bytes. Cleanup can then enter the same parser whose input buffer the read still owns. The observed wire output was the expected empty response followed by spurious 405 and 400 responses. Both socket and channel regressions also showed request retirement overtaking a listener method that was still executing.

`Mu3AsyncHandleImpl` now joins output completion with body-reader retirement. Body-listener registration claims the request before dispatch and rejects registration after completion has been requested. Once accepted output drains, the reader stops scheduling reads and delivery, retains any running read or listener method, and releases parser ownership only after those tasks return. Deferred chunk acknowledgements no longer restart the reader after completion, and its independently owned delivery buffer is not reused. The completed handle drops its reader reference.

HTTP/1 retains a blocking read already in progress until actual input, failure or its deadline lets it return; cleanup then exclusively discards the remaining body. HTTP/2 switches its body queue into discard mode, returning reusable credit and waking a waiting reader. Its protocol stream still retains an unfinished peer upload until END_STREAM. Error-callback rejection also completes the exchange rather than re-entering a failure method that has already published its terminal state.

The executor fixtures now establish request dispatch before deliberately blocking the internal domain. This preserves their original checks for immediate body ownership and rejection of late writes on channel listeners. The connection-task-queue shutdown test explicitly selects the socket adapter; channel queued-exchange shutdown has separate coverage.

The broader channel run exposed a WebSocket error-ordering race as well: a send could return its IO exception before the independently scheduled error notification was reserved. Its receive callback could then return, allowing the reader's disconnect notification to win. Writer failure is now reserved before aborting transport or completing the send, while application dispatch remains outside writer ownership. Read timeouts likewise reserve their original cause before abort can trigger another notification. Tests check publication before caller release, repeat the caught-write wire case, and inject a competing timeout during abort.

These changes establish the ownership boundary for resumable body reads. HTTP body reads and HTTP/1 unread-body cleanup still use blocking stream operations at this stage; readiness notifications and asynchronous response rendering remain subsequent work.

Validation on 11 October 2026: all four initial ownership regressions failed before the fix and passed afterward. The channel body/async-output/cancellation/timeout/executor matrix passed 164 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip. Java 21 with Error Prone/NullAway additionally passed 352 channel WebSocket and slow-peer lifecycle cases. After the final read-timeout publication guard, all 74 targeted reader/writer/channel/error-ordering cases passed. The earlier Java 21 socket/async body run passed 115 tests with one skip, and the HTTP/2 body/upload-retirement run passed 36 tests without skips. These focused checks follow the 4,977-test full build recorded for the WebSocket output stage; they do not claim a new full build for the body handoff changes.

## Resumable HTTP request-body reads

Asynchronous body listeners now use polling views of channel HTTP/1 input and the HTTP/2 body queue. A read returns available bytes, EOF, or a temporary-empty result. Temporary emptiness installs one cancellable readiness notification and releases the internal worker. Input, timeout and response completion compete for that wait once; notifications only publish state and schedule internal work. A rejected internal dispatch uses independent terminal cleanup so a caller-runs application executor cannot put its error callback on the selector or timer.

HTTP/1's blocking and polling views share framing, body-size limits, trailers and terminal states. The parser supplies bounded polling and reports buffered decoder input before waiting for transport bytes. No decoder rewrite or extra whole-body copy is involved. Blocking public streams keep their existing behavior, including the socket adapter. HTTP/2 shares its existing queue-consumption and credit-return logic between both views; padding-only DATA without END_STREAM does not wake a body reader or extend its deadline.

The read deadline runs while waiting for input and is cancelled before delivery to an application listener. A deferred acknowledgement therefore holds the delivery buffer without running a peer-read deadline. Completion cancels the notification and timer, retains any active bounded read or callback method, and ignores late acknowledgements and stale timer notifications. HTTP/1 timeout failures retain their 408/connection-close behavior; HTTP/2 408 responses omit connection-specific headers and leave other streams usable.

Real-listener tests suspend eight HTTP/1 uploads or sixteen HTTP/2 uploads with one internal worker and one application worker, then complete an independent healthy request. HTTP/1 covers fixed and chunked framing over plaintext and TLS, including trailers; HTTP/2 covers plaintext and TLS and early completion of every suspended upload. Controlled deadlines cover input winning, timeout winning, cancelled notifications firing late, deferred acknowledgements, empty DATA and timer rejection. Component tests check every split in representative fixed/chunked bodies, sticky parse/size/EOF/timeout failures, credit accounting, notification callbacks outside storage locks, and 500 offer/registration races.

Unread HTTP/1 body cleanup still blocks while draining a peer upload, and asynchronous response writes still use blocking output adapters. Those are the next migration steps; the default listener remains the socket adapter pending the remaining gates.

Validation on 11 October 2026: the channel body/async API/output/cancellation/timeout/executor matrix passed 218 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip per runtime. Java 21 used Error Prone/NullAway. The preceding Java 21 focused parser/reader checks passed 93 tests without skips, and the broader mixed-backend async API check passed 110 tests with one skip. These are targeted stage checks; a new full build and the independent conformance/workload campaigns remain required.

## Resumable unread-body cleanup

Channel HTTP/1 exchange completion now drains an unread body in bounded parser turns and suspends on input readiness. The exchange retains parser ownership and its admission slot while waiting, without occupying an internal worker. A completed drain hands buffered pipelined bytes back to the connection reader. This covers both ordinary handlers that ignore an upload and asynchronous responses completed before consuming it.

Polling and blocking discards share body accounting, trailer publication, size limits and terminal states. Cleanup records its result or exception once and supplies that outcome to response finishing; it does not enter the body parser again. Preserving the distinction between failed cleanup and a thrown size-limit exception also preserves the existing treatment of already-started responses. Multipart temporary-file cleanup still runs on a worker after body draining. Rejections that close the connection, handler-admission rejection and fatal exchange failure continue to skip draining, including Expect peers that may never send their rejected body.

The shared `InputWait` resolves input, deadline and cancellation once, including cancellation during timer registration. Discard deadlines retain the blocking adapter's failed-cleanup semantics. Input EOF, shutdown, rejected timer tasks and rejected continuation tasks retire the exchange; rejection cleanup stays off notifier threads. A retained cleanup exception cannot hide an `Error` from the exchange's retirement path.

Real-listener tests hold eight ignored uploads against one internal worker and one application worker, complete an independent request, and then pipeline a second request behind every drained body. They cover fixed/chunked framing, synchronous/asynchronous completion, plaintext/TLS, trailers, stale deadlines and request accounting. Fourteen parity cases compare socket and channel wire output, completion state and trailer availability for valid, malformed, oversized, incomplete and timed-out uploads, before and after response output starts. Controlled input/deadline tests include 500 races and timer-publication cancellation; byte-at-a-time discard checks begin with partially consumed decoder storage.

Asynchronous response output and final response rendering still use worker waits for transport or HTTP/2 flow control. Those are the next implementation stages before selecting the channel listener by default and running independent conformance and workload gates.

Validation on 11 October 2026: the expanded channel body/cleanup/async-output/executor/rejection/framing matrix passed 361 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip per runtime. Java 21 used Error Prone/NullAway. Earlier focused integration and parity/race runs passed 90 and 68 tests respectively, without skips. The listener default was restored after the matrix. The next full build and independent campaigns remain outstanding.

## Response queue completion and callback ownership

`AsyncResponseOutput` now accepts an internal writer that returns a completion future. A pending write retains its source buffer and suspends the serial drain; completion schedules another bounded turn. The queue processes at most 64 immediately completed writes per turn. Its existing blocking writer constructor remains an adapter, so this stage establishes the completion boundary without claiming that response formatting has already stopped using blocking streams.

Cancelling a public write aborts the response and waits for the actual writer to release the borrowed source. It does not cancel the internal acknowledgement future. Later queued writes retain their original ordering and failure cause. Executor rejection during resumption finishes cleanup on an independent task, and abort failures are retained as diagnostics without stranding unstarted writes.

Write callbacks are reserved in their serial application mailbox before dispatch is deferred beyond the I/O turn. A caller-runs callback can consequently wait for a subsequent write without holding the queue's serial owner. Exchange retirement waits for callback dispatch acceptance or rejection, while accepted callbacks can execute later. Entries that join an earlier body callback's still-pending submission share its dispatch outcome. Cancelled entries removed from the output queue are also counted until their public futures are settled, preventing an empty queued runner from retiring the exchange prematurely.

Controlled tests cover one-worker suspension, ordered resumption, source ownership during cancellation, exceptional futures, bounded turns, rejected continuations, caller-runs reentrancy, callback submission races, removed-queue settlement and abort failure. Native HTTP response writer futures, bounded encoding/rendering and asynchronous final output remain the next integration work.

Validation on 11 October 2026: the channel body/cleanup/output/executor matrix passed 396 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip per runtime. Java 21 used Error Prone/NullAway. The final queue and callback checks passed 153 tests with one skip, and the channel lifecycle/single-carrier/executor run passed 269 tests without skips. The listener default was restored after these checks. Full-build and independent campaign gates remain outstanding.

## Native HTTP/2 response-write acknowledgements

`Http2Stream` now exposes an internal asynchronous frame write. It retains the active frame across transport and flow-control pauses, clears stream ownership before publishing completion, and rejects overlapping raw writes rather than losing the task cancellation must find. Completion listeners only schedule response work. The existing blocking stream path shares the ownership guard and keeps its interrupt-and-join behavior.

`WriteTask` creates its completion future only when requested and publishes it outside the task monitor. Rejecting the unsent remainder of a partially flow-controlled DATA frame no longer releases an active fragment. A separate writer acknowledgement records when that fragment has actually stopped using its storage. Cancellation and later transport failures retain the first failure cause. A timeout in a blocking observer no longer changes the eventual writer outcome.

Controlled tests send a two-byte terminal DATA frame through a one-byte stream window, retain each transport acknowledgement independently, and close peer input during the final fragment. The only internal worker remains available throughout both pauses. Peer reset and local abort tests delay the transport's abort acknowledgement and verify that native write and connection completion still wait. Component tests exercise the coordinator's partial-reset path, early/late future registration, callback lock ownership, sticky failures and observer timeouts.

The final-drain test also exposed a retirement ordering race: the writer could remove the application stream and publish connection completion before its completed-request count was recorded. HTTP/2 now registers request-end accounting and reserves its completion-callback dispatch before publishing the application-retirement command. Native writer futures are a response-layer integration boundary; actual asynchronous response encoding and final cleanup are still subsequent work.

Validation on 11 October 2026: the expanded channel HTTP/2 protocol/output matrix passed 921 tests on each of Java 11, 17, 21 and 25, with zero failures/errors/skips. Java 21 used Error Prone/NullAway. The preceding focused run passed 97 tests, including the legacy socket upload-retirement fault injector; that socket-specific fixture was excluded from the channel matrix. The listener default was restored afterward. Full-build and independent campaign gates remain outstanding.

## Resumable HTTP/1 response-body rendering

Channel HTTP/1 asynchronous writes now run the existing output streams and content encoder in input turns of at most 8 KiB. Each turn captures its produced bytes, drains them through the asynchronous transport writer and suspends while output is pending. The renderer yields after 64 immediate steps. Heap-buffer position and direct/read-only-buffer consumption retain the existing adapter behavior; an empty write still creates the response stream and flushes its headers. Public blocking streams and a response that began synchronously continue to use the same framing and encoder state.

Captured output owns encoder-supplied arrays, because a synchronous encoder can reuse those arrays as soon as its write returns. Ordinary turns stay in memory. A custom encoder may produce an arbitrarily large burst from one input byte or flush; capture spills beyond 32 KiB to a temporary file in the server's configured directory and drains in 8 KiB pieces. File work stays on workers. Spill storage is closed and deleted after drain, cancellation or failure, including continuation rejection. Bytes already produced before an encoder exception drain before that exception is reported. A failed spill retains its earlier readable prefix and makes later writes fail consistently.

The channel claims one transport writer at protocol promotion. Blocking HTTP/1 streams adapt that writer, while asynchronous response rendering and a later WebSocket upgrade use its native acknowledgement interface. Successful-byte accounting follows actual write completion, including TLS ciphertext drain. Interrupting a blocking public write requests transport abort and still joins its acknowledgement before returning the caller's array. An interruption before acquiring the public writer does not abort another caller's active borrow.

Controlled tests cover owned encoder storage, memory/spill boundaries, deletion without reading, spill failure, encoder-prefix failure, empty-write flush failure, buffer kinds and positions, synchronous-to-asynchronous encoder handoff, and rejection at the exact turn boundary with a still-pending output borrow. A real-listener matrix holds three unread 8 MiB responses with one internal worker and one application worker, completes an independent healthy request, cancels every stalled write and verifies request/connection retirement. It covers plaintext, TLS 1.2 and TLS 1.3 with identity, gzip and a custom encoder that emits the entire large response from one input byte.

This stage removes waiting workers from channel HTTP/1 asynchronous body writes. Final response closing/rendering, higher-level HTTP/2 response encoding, transport/thread-policy defaults and independent campaign gates remain outstanding.

Validation on 11 October 2026: the channel rendering/framing/encoder/async-API/WebSocket matrix passed 419 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip per runtime. After the final fatal-error and error-suppression guards, Java 21 with Error Prone/NullAway passed 230 renderer, stalled-listener, shutdown and constrained-carrier cases without skips; the 34 focused renderer/storage/adapter/stalled-listener cases also passed on Java 11, 17 and 25. The preceding mixed-backend Java 21 integration run passed 214 tests with one skip. The listener default was restored. Full-build and independent campaign gates remain outstanding.

## Resumable HTTP/2 response-body rendering

HTTP/2 asynchronous body writes now use the shared bounded renderer. Their response output adapter keeps header blocks as logical frames for the coordinator's HPACK owner, captures encoder-produced DATA in owned byte storage, and submits bounded DATA fragments through native stream acknowledgements. Flow-control and transport pauses release the response worker. Synchronous response streams use the same adapter through its blocking view. Custom encoder bursts use the same bounded-memory spill storage as HTTP/1.

Output preparation is a separate resumable step before encoder construction. HTTP/2 headers previously reached the writer before `ContentEncoder.wrapStream` ran; HEAD/304 HTTP/1 headers likewise flushed before encoder construction. Preserving this boundary lets a peer receive a bodyless response and close its input while an encoder is still initializing. The response still retains the encoder's eventual failure or successful cleanup as part of its completion outcome. Encoder selection and header preparation happen once, while a response that already has an output stream retains that stream's state.

The broad protocol run caught this boundary with a controlled 304 encoder that waits while the peer reads END_STREAM and disconnects. The corrected renderer drains initial headers before entering the paused encoder. Additional HTTP/1 tests cover both transports, plaintext/TLS, HEAD/304 and initialization/write/close failures; they verify one preparation call, header delivery before initialization returns, and delayed exchange completion. The legacy TLS fixture releases its encoder before starting its separate close_notify handshake, because a blocking HTTP/1 socket reader can still be waiting for application completion.

A real-listener matrix suspends three HTTP/2 responses at zero stream credit with one internal worker and one application worker, serves another request, cancels all three writes, reads their resets and checks a PING on the surviving connection. It covers plaintext, TLS 1.2 and TLS 1.3 with identity, gzip and an encoder that emits a large burst from one input byte. The upload-retirement fault injector explicitly selects its required socket adapter, so its Socket.shutdownOutput and blocked-reader checks remain meaningful when the general listener default changes.

Final response closing/rendering and terminal resource cleanup remain the next integration stage. Default selection, a new full build and independent conformance/workload gates still follow that work.

Validation on 11 October 2026: the expanded channel HTTP/2 protocol, response-rendering, HTTP/1 framing, encoder, async-API and executor matrix passed 1,257 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one skip per runtime. Java 21 used Error Prone/NullAway. The preceding initialization and response-ordering checks passed 218 tests without skips. The legacy upload fault injector ran using its explicit socket adapter, and the listener default was restored after the matrix. Full-build and independent campaign gates remain outstanding.

## Terminal response resource cleanup

A focused regression confirmed that failing an asynchronous response after its first successful write skipped both encoder closure and parsed multipart-file deletion. All four combinations of HTTP/1 or HTTP/2 and socket or channel transport reproduced the leak; their successful-response controls released both resources.

Terminal exchange paths now release parsed upload storage without reacquiring the request-body parser. They close opened response encoders through a discard-only output view, so encoder cleanup cannot append a footer, a final HTTP/1 chunk or HTTP/2 END_STREAM to a failed response. This view is present on the legacy socket adapter as well as the channel adapter. Cleanup runs after response/body ownership has returned and before completion accounting and callbacks, including abandoned completion tasks. Rejected completion dispatch uses a separate cleanup task so custom encoder closure does not execute on an I/O notifier.

The regression checks resources inside the completion callback, encoder closure exactly once, wire contents and terminal framing. It covers ordinary completion, explicit failure after output starts, and a second failure while closing the encoder. Existing upload/framing, paused encoder, peer-close, executor-rejection and lifecycle tests cover the surrounding retirement paths. Resumable final output generation and acknowledgement remain the next stage.

The caller-runs executor audit found two further protocol stalls. A peer reset could publish an idle async exchange's completion from the HTTP/2 coordinator, and an upload's final DATA could invoke a completion listener from the reader. A paused encoder close or completion listener consequently prevented an unrelated PING from being acknowledged. Both socket and channel adapters reproduced both failures. Private async-exchange completion now crosses an internal task boundary before application completion dispatch. Response-completion and rejection listeners likewise reserve their shutdown registration before deferred submission to the application executor. Rejected internal dispatch falls back to a separate task without losing that registration. Real-connection tests hold encoder closure, upload-completion listeners and header-rejection listeners paused while checking PING progress.

Buffered writer cleanup also closes its encoder when flushing the writer throws, preserving the first exception and suppressing a later close exception. An existing one-worker, zero-queue executor test caught unnecessary callback resubmission from an application worker; the corrected dispatcher retains that worker's serial application mailbox and defers only submissions arriving outside an application context. The connection-task rejection fixture now explicitly chooses the socket adapter whose blocking task it rejects, while handler-overload recovery continues to exercise the selected listener.

Validation on 11 October 2026: the final channel cleanup, callback-dispatch, framing, encoder, rejection, WebSocket, executor and lifecycle matrix passed 518 tests on each of Java 11, 17, 21 and 25, with zero failures/errors/skips. Java 21 used Error Prone/NullAway. The earlier cleanup/carrier matrix passed 604 cases on each runtime, with 156 virtual-thread skips on Java 11/17 and no skips on Java 21/25; the final mailbox and buffered-writer checks passed another 80 focused Java 21 cases. The listener default was restored after validation. Resumable final output, default selection and the full-build/independent campaigns remain outstanding.

## Resumable final response output

Channel HTTP/1 finalization now separates output preparation, rendering and retirement. Empty responses, buffered bodies, encoder footers, final chunks and handler-overload replies use the captured output path and suspend on actual transport acknowledgements. Parser-rejection responses prepare their headers separately from encoder construction, preserving HEAD header delivery even when a custom encoder pauses during initialization. HTTP/1 completion state, accounting and rejection notifications follow final drain or its failure. The blocking adapter shares the same rendering and retirement phases.

HTTP/2 final cleanup captures buffered DATA and encoder closure, keeps HPACK with the coordinator, and waits for the final native frame acknowledgement before publishing FINISHED. Stream application retirement and resource release follow that outcome; request-end accounting still waits for an unfinished peer upload. Immediate cleanup retains its current application mailbox, while a pending completion schedules another worker turn. Executor rejection has an independent cleanup path.

Controlled transport tests retain the last header/DATA write and HTTP/1 flush separately. They check pending response state, accounting, callbacks, overload retirement, input EOF during final output and failing acknowledgements. Real-listener tests hold three large close-only encoder outputs against one internal worker and one application worker, serve another request, and then either drain every response or reset its peer. They cover HTTP/1 and HTTP/2 over plaintext, TLS 1.2 and TLS 1.3, including zero HTTP/2 stream credit and temporary-output storage cleanup.

A controlled fatal encoder error also exposed an accepted completion notification left in an abandoned application mailbox. Accepted nested cleanup/notification tasks now drain before a fatal error escapes their worker. HTTP/2 finalization resets an unrecoverable stream, releases resources and records completion before propagating the fatal error; handler dispatch does not treat failure in that finalizer as a second handler failure. The regression checks one encoder close, one request completion, the failure notification and reset, and a healthy subsequent stream on both adapters.

The HTTP/1 fatal-close variant also exposed an application failure rethrown while its completion future was observed on the shared selector, stopping the listener. Finalization now propagates fatal failures on its owning worker after cleanup; the readiness driver observes the failed exchange and closes only its connection. The regression covers both protocols and adapters, and verifies that a new connection can still complete a healthy request.

Validation on 13 October 2026: the restored finalization, fatal-error isolation, initialization, body ownership, cleanup, executor, channel and lifecycle matrix passed 283 tests on each of Java 11, 17, 21 and 25, with zero failures/errors/skips. Java 21 used Error Prone/NullAway. The preceding focused run passed 168 tests without skips. The general listener was temporarily switched to the channel backend for the matrix and restored afterward. Async-handler failure ownership, default selection and the full-build/independent campaign gates remain outstanding.

## Handler failure after starting asynchronous work

A handler can start an asynchronous write or body read and then throw, including after it has already requested successful completion. Previously, the controller handled that exception and retired the request without joining the async operation. Controlled tests reproduced early HTTP/1 accounting, missing HTTP/2 transport cancellation while output was still borrowed, and completion notifications while a body callback still held its buffer.

Handler unwinding now fails the async output queue even if successful completion was already requested, then joins the same output and body ownership used by normal async completion. Exception rendering and terminal resource cleanup follow that join. An earlier output failure remains the primary cause unless the handler throws a fatal error; fatal errors propagate only after retirement. This also covers an async exchange whose private completion was already published before the handler threw.

Delayed-acknowledgement tests retain the actual transport borrow during abort for HTTP/1 and HTTP/2, with and without an earlier `complete()` call. Real-connection tests hold a request-body callback while its handler throws an ordinary or fatal error, across both protocols and both adapters. They verify deferred completion/accounting, stable body storage, fatal propagation and ignored late acknowledgements. All 12 regressions failed before the change. The restored isolated fix passed 110 surrounding tests, and the initial main-checkout Java 21 run passed 181 tests with one existing skip and Error Prone/NullAway enabled.

Validation on 13 October 2026: the channel handler-failure, async output/body, exception handling, encoder, callback and executor matrix passed 206 tests on each of Java 11, 17, 21 and 25, with zero failures/errors and one existing skip per runtime. Java 21 used Error Prone/NullAway. The listener default was restored afterward. Default selection and the full-build/independent campaign gates remain outstanding.

## Channel transport and conservative thread defaults

The channel listener is now the default on the branch. Each listener has a platform readiness loop, and TLS delegated tasks use a bounded platform pool. HTTP/1, HTTP/2, WebSocket and async response operations suspend on readiness or completion rather than retaining a task worker while they wait. Public blocking request and response streams retain their existing handler-facing behavior; a handler that chooses to block still occupies its application worker.

`ThreadingMode.AUTO` uses cached platform task executors before Java 25 and virtual task executors from Java 25. Explicit `VIRTUAL` still supports Java 21 and later. A supplied application executor retains its existing ownership and behavior. The legacy socket adapter remains an internal compatibility/fault-injection option. The channel listener tests now exercise the actual default; virtual-carrier stress tests explicitly request `VIRTUAL`, while stock-wrapper AUTO cases also run on Java 11, 17 and 21 with platform workers.

The first clean default run found two state-test fixtures that passed a null request into a response constructor, plus an SSE disconnect compatibility failure. State-transition tests now operate on actual responses. Readiness can observe an SSE disconnect before the next publisher write reaches the output stream; that failed terminal state is translated to the publisher's documented `IOException`, while other illegal states retain their existing exception behavior. The disconnect regression covers both adapters over HTTP, HTTPS and HTTP/2. The previously disabled HTTP/1 suspended-request reset test is also re-enabled: channel readiness can now cancel that exchange without waiting for another application write.

The initial Java 21 clean run executed 5,215 tests with one failure, two errors and one skip; it is failure evidence, not a completed validation gate. After these corrections, the focused Java 21 default/streaming/threading checks passed 104 tests without skips, with Error Prone/NullAway enabled. An isolated replay of the HTTP/1 reset test passed on Java 11, 17, 21 and 25. Clean four-JDK verification and the independent conformance/workload campaigns follow.

The corrected Java 21 `mvn -Pnullaway clean verify` run passed on 13 October 2026: 5,220 tests, zero failures/errors/skips, plus Javadocs, packaging and dependency analysis. The Java 11/17/25 clean runs and independent campaign gates remain pending at this checkpoint.

## Integrated candidate and independent protocol validation

The tested production candidate is `98ae1f16de03b09fd2d4b4d904c333c95cc6271e`.
Separate immutable checkouts prevent the functional and independent campaigns
from rebuilding each other's classes. Both use conformance harness
`76b67b04734c4803c4be4caac091329d43005902` and Mu3 reference
`8a52716561df73ed1f39abb4719d3ea2c8123e02`.

On 13 October 2026, the default-settings functional campaign passed all 140 Mu4
checks across Java 11, 17, 21 and 25; the matched-settings campaign also passed
140. Each reference campaign passed 136 checks and explicitly excluded the four
Mu4-only cleartext HTTP/2 prior-knowledge checks. Coverage includes fragmented
HTTP/1, uploads, multipart, streaming/gzip, SSE, WebSockets, HTTP/2 credit/reset,
completion timing, graceful/deadline shutdown and independent Java clients.

Each functional comparison contains 100 paired semantic comparisons, of which
76 retain the harness's review verdict. Every semantic difference is the addition
of `accept-encoding` to `Vary`, including merging with REST's `origin` value;
status codes, decoded body hashes/lengths and trailers match. This follows
`ContentEncoder.defaultPrepare`, which predates the channel migration. No raw
verdict was rewritten, and individual functional timings do not establish
performance parity.

The independent matrix completed against both implementations on all four JDKs:

| Tool/profile | Mu4 on each JDK | Mu3 reference on each JDK |
| --- | --- | --- |
| h2spec 2.6.0, strict TLS/ALPN, RFC7540/7541 | 147 pass, zero failures | 133 pass, 14 failures |
| Autobahn 25.10.1, selected 247-case WebSocket core profile | 244 pass, zero failures, three informational | 166 pass, 78 failures, three informational |

All twelve candidate informational observations were inspected in their original
JSON reports. Case 7.1.6 received both queued echoes and a clean 1000 close;
7.13.1/7.13.2 received clean 1002 responses to invalid peer close codes 5000 and
65535. These match the behavior already reviewed in the harness's
`independent/decisions/websocket-informational.json`. Their INFORMATIONAL verdicts
and the runner's strict exit status of 1 remain intact. There are no new or
changed informational behaviors in this replay.

The independent profile excludes Autobahn 9.* large-message/performance cases
and 12.*/13.* compression-extension cases. Its WebSocket fixture uses plaintext;
WSS is covered by the repository tests, not this external profile. h2spec 2.6.0
is not a certification of every RFC9113 requirement. Generated-request, pressure,
connection-capacity and sustained-traffic evidence follows separately.

Raw logs, tool reports, fixture identities and the per-observation review hashes
are retained in the workspace sibling `nonblocking-validation/`, including
`functional-defaults-98ae/`, `functional-matched-98ae/`, `independent-98ae/` and
`review-98ae.json`.

The corrected clean verification matrix completed on 13 October 2026. Each of
Java 11, 17, 21 and 25 ran 5,220 tests with zero failures/errors, followed by
Javadocs, packaging and dependency analysis. Java 11/17 skipped 176 virtual-thread
cases; Java 21/25 skipped none. Java 21 enabled Error Prone/NullAway. All four
runs used the committed channel default, without a temporary backend override.
The earlier failed Java 21 run remains recorded above and in its separate log.

## Capture allocation bound under uneven encoder writes

The final memory audit found that the 32 KiB spill threshold bounded captured
payload length but not the backing-array capacity of `ByteArrayOutputStream`.
For example, two writes ending at the threshold could retain a 40,000-byte or
65,534-byte array because the generic stream doubled its previous capacity.
This did not corrupt output or create unbounded growth, but exceeded the
intended retained-array limit.

`RenderedResponseBytes` now owns its byte array directly and caps each growth at
32 KiB. Payloads beyond the threshold still spill; borrowed drain buffers and
file cleanup retain the same ownership boundary. Resizing can transiently hold
both old and new arrays, so the retained-array limit is not a total-allocation
or process-memory claim.

Three uneven-write regressions failed before the change, observing capacities
32,770, 40,000 and 65,534. The drain checks now assert the backing-storage bound
as well as exact output and bounded chunks. The rendering, capture, finalization,
initialization, failed-resource cleanup, custom encoder, gzip and response-stream
matrix passed 235 tests on each of Java 11, 17, 21 and 25, without failures,
errors or skips. Java 21 enabled Error Prone/NullAway. The external workload
campaigns remain pinned to `98ae1f16`; their results identify that revision
explicitly rather than attributing its measurements to this allocation change.

## Generated requests, transfer pressure and idle capacity

The `98ae1f16` generated-request campaign used seed 9113 and 1,000 generated
requests per implementation/runtime, plus the 29 functional socket scenarios.
Across Java 11/17/21/25, Mu4 passed 4,116 checks; Mu3 passed 4,112 and excluded its
four unsupported cleartext-prior-knowledge checks. The generated POST bodies
contain 1–2,047 bytes and use plaintext or TLS with fragment sizes 0 (whole
write), 1, 2, 7 or 64 bytes. Of 4,100 paired semantic comparisons, 1,384 match
exactly and 2,716 retain review verdicts solely for the pre-existing `Vary`
addition. There are no other semantic mismatches or failed cases.

The separate pressure profile passed for both implementations on all four JDKs.
Each fixture completed two rounds of eight concurrent 8 MiB upload/download
operations with full length/digest checks, cancelled a declared 16 MiB HTTP/2
upload and a 64 MiB response, reused the surviving connection, and delivered a
complete 4 MiB response during graceful shutdown. Each cancelled request retired
exactly once. No resource guard fired. These timings are diagnostic because
other validation traffic could be active; they are not throughput comparisons.

All twelve Mu4 idle-capacity cases passed with a 512 MiB maximum heap, a ten-second
connection ramp, ten-second warm-up and twenty-second idle hold. Every case kept
1,000 connections open throughout the hold and successfully reused all 1,000
before teardown, with zero errors/resource violations. The table records total
JVM platform threads at the end of the hold, including JVM, transport, delegated
TLS and cached task workers; it is not a count of connection-specific workers.

| Runtime | HTTP/1 | HTTPS/1 | TLS HTTP/2 |
| --- | ---: | ---: | ---: |
| Java 11 | 27 | 76 | 77 |
| Java 17 | 22 | 58 | 62 |
| Java 21 | 21 | 52 | 48 |
| Java 25 | 24 | 36 | 36 |

The same Java 21 reference checks passed for Mu3 with 31, 41 and 39 platform
threads respectively. The observations establish that this workload does not
retain one platform thread per idle connection. They do not establish a maximum
connection capacity or a general total-memory envelope. Immediate teardown
snapshots can precede asynchronous socket retirement; sustained recovery is
assessed separately. Capacity diagnostics overlapped the functional campaign;
paired throughput measurements start only after the other traffic campaigns
and builds finish.

Evidence directories are `differential-matched-98ae/`, `pressure-98ae/` and
`capacity-{mu4,mu3}-jdk*-{http1,https1,h2}-98ae/` in the workspace validation
sibling. The final allocation-fix candidate has its own immutable fixture for
subsequent protocol and pressure replays.

## Paired throughput diagnostics

The `98ae1f16` default-settings campaign ran three alternating Mu3/Mu4 pairs per
workload on Java 21 and 25, with eight closed-loop clients, ten seconds of warm-up
and twenty seconds of measurement per fixture. All 48 measured fixtures completed
without request errors or resource-guard violations. The generator checked
15,611,458 measured responses; warm-up traffic was checked separately.

| Runtime | Workload | Mu3 median responses/s | Mu4 median responses/s |
| --- | --- | ---: | ---: |
| Java 21 | Small HTTP/1 response | 30,091 | 30,409 |
| Java 21 | Small HTTPS/1 response | 26,505 | 29,379 |
| Java 21 | 1 MiB chunked HTTP/1 response | 945 | 434 |
| Java 21 | Small TLS HTTP/2 response | 6,140 | 5,545 |
| Java 25 | Small HTTP/1 response | 32,066 | 30,903 |
| Java 25 | Small HTTPS/1 response | 27,052 | 27,803 |
| Java 25 | 1 MiB chunked HTTP/1 response | 992 | 408 |
| Java 25 | Small TLS HTTP/2 response | 6,256 | 5,326 |

These workstation measurements retain the harness's inconclusive performance
verdicts. Generator saturation and host interference are not excluded, and there
is no open-loop latency SLA measurement. The repeatable bulk gap and smaller
HTTP/2 gap require a comparison of the same Mu4 revision with its socket and
channel adapters before attributing them to the migration. That diagnostic also
compares fixed-length bulk output and records client receive-call counts. The
HTTP/1 buffering/framing code already existed before the migration; no buffer
size or output behavior has been changed merely to improve these figures.

The raw paired measurements are in `throughput-defaults-98ae/`. Recovery and the
final allocation-fix replay follow separately.

## Final allocation-fix replay and bounded recovery

The immutable `4f4a2336b5b6aa9bdebd82143b4364a35a030145` fixture repeated the
independent checks on Java 11/17/21/25: 147 h2spec passes and 244 Autobahn passes
plus the same three informational cases per runtime, with zero failures. All
twelve informational observations match the preceding replay's received-message
sequence, close code and clean-close outcome. Original verdicts remain unchanged.
This was a candidate-only replay; the unchanged Mu3 reference results are retained
in the preceding paired campaign. The final candidate also passed all 140
functional checks and all four transfer-pressure fixtures.

The separate `98ae1f16` recovery campaign first opened and closed progressive
10/100/500/1,000-connection groups, then ran 24 five-second traffic cycles per
runtime, alternating HTTP/1, HTTPS and TLS HTTP/2 with eight clients. Each fixture
ran for 120 seconds after the ramp. All checked responses succeeded, no resource
guard fired, and all handled requests—including the initial 1,000 requests—had
matching completion counts after recovery.

| Runtime | Checked requests during traffic cycles | File descriptors before / after | Failed requests |
| --- | ---: | ---: | ---: |
| Java 11 | 2,477,591 | 54 / 54 | 0 |
| Java 17 | 2,505,214 | 49 / 49 | 0 |
| Java 21 | 2,642,276 | 49 / 49 | 0 |
| Java 25 | 2,571,224 | 49 / 49 | 0 |

These 10,196,305 requests establish bounded-run correctness and descriptor
recovery. Heap usage fluctuated with collection; endpoint RSS or heap alone is
not a leak verdict. The short campaign does not satisfy a multi-day soak or
application-deployment release gate. Raw time series and diagnostics are in
`soak-defaults-98ae/`; final-candidate results and reviewed report hashes are in
`independent-4f4a/`, `functional-defaults-4f4a/`, `pressure-4f4a/` and
`review-4f4a.json`.

## Controlled HTTP/1 bulk diagnosis

A separate fixture compared the same `4f4a2336` production source with the socket
and channel adapters. Its sole configuration extension selected the internal
socket compatibility adapter; both variants otherwise used default settings.
Java 21 ran two alternating pairs per workload, with eight clients, ten seconds
of warm-up and twenty seconds of measurement. All responses were valid and
resource guards remained clear.

| Workload | Socket responses/s, two runs | Channel responses/s, two runs |
| --- | ---: | ---: |
| 1 MiB chunked HTTP/1 | 1,162 / 1,180 | 429 / 429 |
| 1 MiB fixed-length HTTP/1 | 546 / 544 | 612 / 631 |
| Small TLS HTTP/2 | 5,291 / 5,419 | 5,406 / 5,448 |

The large channel regression is specific to chunked HTTP/1 in this comparison.
The smaller Mu3/Mu4 HTTP/2 gap does not establish a migration regression: the two
Mu4 adapters perform similarly here. The fixed-length control also has no
channel regression. Client receive calls per chunked response increased from
about 17 with the socket adapter to 207–209 with the channel adapter.

`BufferedOutputStream` flushes an already buffered chunk prefix separately when
an incoming payload is at least its capacity. An 8 KiB body write can therefore
produce a tiny framing write followed by a payload write, each awaiting the
channel owner. `Http1OutputBuffer` instead fills the existing buffer with the
prefix and the beginning of the payload before handing it off. It retains the
configured capacity, serializes buffer ownership with a `ReentrantLock`, and
preserves explicit flushes and connection ownership. Fixed-length responses,
HTTP/1.0 and explicitly unbuffered responses keep their existing path.

An isolated comparison of this coalescing change measured 650 / 649 responses/s
against 435 / 431 before the change, with about 107–109 client receive calls per
response instead of 206–209. A more complicated prototype that reserved extra
space for complete chunk frames measured 648 / 649 and was not adopted. The
simpler coalescing change gives the same improvement without changing chunk
encoding or allocating a larger response buffer. These are local throughput
diagnostics, not evidence of socket-throughput parity or a latency SLA.

Immutable build manifests, checked response counts and receive-call measurements
are in `nonblocking-validation/bulk-transport-4f4a/`, `coalescing-comparison/` and
`frame-buffer-comparison/` beside the repository. The harness source and all
production source/classpath inventories are recorded with each fixture.

The transport ring also retained its offset after draining completely. A prior
short write could therefore split a later full-buffer write at the old offset.
`TransportOutputBuffer` now resets that offset only when empty, while holding its
storage lock and after the sink returns its byte acknowledgement. It does not
move a pending TLS prefix or change the completion boundary. Four controlled
regressions first failed with split writes, then passed with the correction;
the isolated transport/TLS/response checks passed 143 cases on Java 21 with
NullAway. The additional throughput effect remains to be measured separately.

## Disconnect publication before cancellation

The coalescing validation passed a complete Java 21 build with 5,234 tests and
no failures/errors/skips, then 1,666 focused cases on each of Java 11 and 17 with
no failures/errors and 170 runtime-specific skips. The Java 25 run found one
failure in those 1,666 cases: an aborted upload could expose `ERRORED` where the
public response state should be `CLIENT_DISCONNECTED`. This failed run remains
recorded in `coalescing-main-jdk25.log`; it is not a passed gate.

The readiness owner had detected an I/O failure, but failing the input bridge
and scheduling generic protocol cancellation could let `Http1Connection.abort()`
publish `ERRORED` before the body reader observed the reset. The first terminal
state wins, so a later input-failure notification could not correct it.
`ChannelConnection` now publishes the observed transport I/O failure before
waking bridge consumers or scheduling cancellation. Internal unchecked failures,
local shutdown, timeouts and HTTP/2's existing completion rules retain their
separate paths.

Four deterministic tests hold a handler before its body read, reset the actual
TCP connection, wait for protocol cancellation, and only then release the read.
They cover plaintext/TLS with PLATFORM and AUTO, check the state at the read
failure and at response completion, and verify that request ownership remains
with the running handler. All four failed before the correction on Java 25.
The combined output/ring/disconnect changes then passed 243 Java 21 transport,
TLS, termination, body-cleanup and finalization cases with NullAway and no skips.

The Java 25 replay passed 115 channel, termination, ring and coalescing cases,
including the original failure and all four controlled regressions, with no
failures/errors/skips. The final clean build, broader runtime matrix and pinned
external protocol replay follow this checkpoint.

## Final source replay (14 October 2026)

Production revision `2eaefc8cf1f43cfb81f9e89b694ee3a9390bf18d` includes the
coalescing/ring change and disconnect-publication correction. An immutable clean
checkout was built with harness `76b67b04734c4803c4be4caac091329d43005902`;
its recorded production source digest is
`c6d9434e8f9cb26c6d7176592f6785f3e78849c9c6212d84036216d92640baac`.

On each of Java 11, 17, 21 and 25, the independent replay again recorded 147
h2spec passes and 244 Autobahn passes with three informational cases. All twelve
informational reports were inspected: their received-message sequences, remote
close codes and clean-close flags exactly match the reviewed `4f4a2336` reports.
The strict harness exit remains 1, and the raw INFORMATIONAL verdicts are
preserved. The earlier core-profile exclusions and h2spec protocol-version
limitations still apply.

The same pinned source passed all 140 default-settings functional cases and all
four transfer-pressure fixtures. The pressure runs repeated full upload/download
checksums, cancelled-upload and cancelled-response recovery on the same HTTP/2
connection, exactly-once retirement, and complete response delivery during
shutdown. The unchanged Mu3 reference was already checked in the earlier paired
campaigns; this replay is candidate-only.

Raw reports, exact build identities and reviewed report hashes are retained in
`nonblocking-validation/independent-2eaef/`, `functional-defaults-2eaef/`,
`pressure-2eaef/` and `review-2eaef.json` beside the repository. The 1,000-connection
capacity and bounded-soak observations above belong to their stated `98ae1f16`
source pin; they are not relabelled as measurements of this later revision.

The final Java 21 `mvn -Pnullaway clean verify` run passed on 14 October 2026:
5,242 tests, zero failures/errors/skips, plus Error Prone/NullAway, Javadocs,
packaging and dependency analysis (`final-output-jdk21.log`).

The final transport/protocol/response/lifecycle matrix passed 1,703 cases on
each of Java 11, 17 and 25, with zero failures/errors. Java 11/17 skipped 170
runtime-specific cases; Java 25 skipped none. The matrix includes the controlled
disconnect ordering, ring alignment, framing, buffer ownership, TLS, constrained
carriers and cancellation/shutdown cases. Logs are `final-output-jdk11.log`,
`final-output-jdk17.log` and `final-output-jdk25.log`.

## Final bulk comparison (14 October 2026)

After all builds and other protocol traffic finished, a final Java 21 comparison
ran two triples in opposite order: the original `4f4a2336` channel output, the
isolated coalescing-only prototype, and committed `2eaefc8c` with both output
corrections. Each fixture used eight clients, ten seconds of warm-up and twenty
seconds of measurement, serving 1 MiB chunked HTTP/1 responses with default
settings. All 81,312 measured responses passed status/body checks, their count
matched the server's handled-request delta, and no resource guard fired. Warm-up
responses were checked separately.

| Variant | Responses/s, two runs | Client receive calls/response, two runs |
| --- | ---: | ---: |
| Original channel output (`4f4a2336`) | 431 / 432 | 203.9 / 203.8 |
| Coalescing only (isolated prototype) | 650 / 664 | 105.9 / 103.2 |
| Final output (`2eaefc8c`) | 1,033 / 854 | 25.8 / 17.4 |

The final implementation approximately doubles throughput in both runs compared
with the original channel output. Filling the response buffer before handing it
off and aligning an empty transport ring remove separate sources of fragmented
writes without increasing either buffer's capacity or acknowledging bytes early.
The final samples still vary substantially and remain below the earlier socket
control's 1,162–1,180 responses/s. That residual difference remains a documented
tradeoff; these local closed-loop results do not establish throughput parity,
an open-loop latency SLA, or performance on a deployed workload.

The immutable source/class inventories, script digest, all six measurements and
request/receive counts are in `nonblocking-validation/final-bulk-comparison/`;
`review-2eaef.json` records the checked totals and report hashes. The earlier
120-second recovery checks remain bounded soaks, with no claim of multi-day
leak qualification. The supported branch implementation is validated by the
build, protocol, ownership, cancellation and recovery checks described above;
application-specific capacity and release criteria remain deployment concerns.

## Native-load performance investigation (17 October 2026)

The native-load comparison in
`mu-conformance/target/nio-comparison-20261014/report.html` exposes much larger
channel-transport costs than the earlier Python-driven bulk comparison. That
original report and its incomplete campaign are preserved. Passing protocol and
ownership tests does not establish throughput acceptance; the earlier bulk
figures are not evidence of saturation or parity with the blocking implementation.

The new campaign is in `nonblocking-validation/performance-20261017/` beside the
repository. It uses frozen source and class inventories, fresh JVMs, native
h2load 1.70.0, ten seconds of warm-up and twenty seconds of measurement. Server
CPUs are `0-3,8-11`; generator CPUs are `4-7,12-15`. The server has a 512 MiB
maximum heap and a 1 GiB cgroup with swap disabled. Body hashes are checked before
and after each trial, successful statuses and request counts are reconciled, and
generator saturation and incomplete connection sets exclude speed comparisons.
Diagnostic JFR runs are separate from throughput trials.
The host is an AMD Ryzen 9 8945HS on Linux 7.2.5-3-omarchy; each CPU set contains
four physical cores and both SMT siblings. These are local closed-loop tests at
fixed concurrency, not fixed offered-rate latency measurements.

Controls separate transport changes from worker policy: starting NIO
`b1b007232aa882c477b295aee3d96e9db53fc582`, the candidate channel transport, its
same-source internal socket adapter, and earlier blocking
`50ad1a7072fffe959770531204a09b2ddd3b4637` run with explicit PLATFORM on Java 21.
The core workloads repeat in opposite order. Expanded uploads, multipart,
compression, sequential traffic and pipelining receive exploratory checks;
Java 25 AUTO and Java 21 explicit VIRTUAL are recorded separately. The primary
comparison does not mix different worker defaults.

The production changes address measured costs while retaining the existing
transport and TLS ownership rules:

* Encrypted writes have a bounded 64 KiB drain budget, so an ordinary TLS record
  is not split merely because its ciphertext is larger than 8 KiB of plaintext.
  Read readiness is retained until a nonblocking read would block; a transport
  owner does not wake its own selector when scheduling another turn.
* Connections are assigned permanently across a configurable readiness group.
  `withNioThreads(0)` selects `min(4, availableProcessors)` per listener; positive
  values select an explicit count. TLS delegated tasks still share one bounded
  two-to-eight-worker pool and 256-task queue per listener. Additional owners
  improve parallel throughput but can increase CPU cost per request.
* The output ring has nine bytes of frame-header headroom: 8,201 bytes instead of
  8,192. A default HTTP/2 DATA chunk can therefore drain without a separate
  nine-byte plaintext tail and TLS record. Input capacities are unchanged.
* The HTTP/2 writer reuses encoding scratch from the existing bounded cache of
  64 idle 16 KiB arrays; an idle writer retains its 256-byte small buffer. Larger
  individual encodings are discarded after use. Batches combine only ready
  frames, with a 64-frame limit and an 8 KiB target, and flush before waiting for
  flow-control credit. GOAWAY and protocol errors remain ordering boundaries.
* Transport progress records fully drained byte prefixes, including TLS
  ciphertext. A complete terminal frame can publish its reader-facing fence and
  complete on the serialized writer while later batch bytes remain pending.
  Partial frames retain admission; later failures do not undo a drained earlier
  frame. The shared encoding remains borrowed until the entire transport write
  is acknowledged, including after cancellation. Progress notifications only
  schedule work; they do not run application callbacks on the readiness owner.
* HTTP/1 request preparation uses Mu's own application executor directly, then
  its existing application-task queue for the handler. A caller-supplied
  executor retains the protective internal handoff because submission itself
  can block or execute code. Preparation, rejection writes and cleanup remain
  off the readiness owner.

Regression coverage includes permanent owner assignment, shared TLS-task
capacity, selector failure across all owners, retained running handlers,
fragmented duplex reads, zero and partial output drains, and exact final TLS
record-byte acknowledgement on TLS 1.2/1.3. HTTP/2 tests hold later batch output
while earlier frames finish, fail later output before the writer resumes, and
churn the shared scratch pool while an encoding remains borrowed. These checks
separate frame publication, task completion and buffer reuse explicitly.

The frozen candidate's production source SHA-256 is
`1432a813bb5c272e285189d69e798a33ea643f1d2db88c648a3ac6ecb50d495c`.
Clean `verify` completed on Java 11, 17, 21 and 25. Each run discovered 5,282
tests with no failures or errors; Java 11 and 17 skipped 176 tests that require
virtual-thread support, while Java 21 and 25 skipped none. Java 21 also ran the
NullAway/Error Prone profile. The external functional matrix passed 140 cases
across those runtimes. All four transfer-pressure runs passed concurrent 8 MiB
uploads/downloads, cancellation followed by HTTP/2 connection reuse, and graceful
shutdown with a transfer in progress.

Independent h2spec 2.6.0 passed all 147 cases on each runtime. Autobahn passed
244 cases per runtime and classified three cases (7.1.6, 7.13.1, 7.13.2) as
informational. Their behavior, received messages, close codes and clean-close
flags match the earlier `independent-2eaef` reports. The strict harness exit of
one and its `candidate_pass: false` remain in the raw results; the separate
`validated-independent-review.json` records the comparison instead of rewriting
those outcomes.

Five-minute mixed HTTP/1, HTTPS and HTTP/2 soaks with eight clients completed on
all four runtimes: 6,000,158 requests on Java 11, 6,184,159 on Java 17, 6,109,221 on
Java 21 and 5,892,002 on Java 25. No request errors or resource-guard violations
were reported. These short runs exercise repeated traffic and recovery; they do
not establish a multi-day leak bound.

The 48 core native trials completed with no correctness failures or generator/
connection exclusions. Medians of two runs in opposite order, in requests/s:

| Java 21 PLATFORM workload | Starting NIO | Candidate NIO | Ratio | Candidate socket adapter | Earlier blocking |
| --- | ---: | ---: | ---: | ---: | ---: |
| HTTP/1, 128 B, 32 connections | 72,007 | 120,955 | 1.68x | 151,308 | 175,787 |
| HTTP/1, fixed 1 MiB, 8 connections | 1,102 | 2,918 | 2.65x | 6,753 | 6,841 |
| HTTPS/1, fixed 1 MiB, 8 connections | 486 | 2,190 | 4.50x | 5,176 | 5,227 |
| TLS HTTP/2, 1 MiB, 8 connections | 326 | 1,211 | 3.71x | 1,794 | 1,807 |
| HTTP/1, chunked 1 MiB, 8 connections | 1,081 | 2,859 | 2.64x | 6,488 | 4,492 |
| TLS HTTP/2, 128 B, 1 connection / 32 streams | 55,648 | 139,896 | 2.51x | 162,366 | 170,371 |

Throughput gains do not imply lower CPU cost throughout. CPU microseconds per
request changed from 41.0 to 53.7 for small HTTP/1 responses, 1,166.8 to 1,711.6
for fixed HTTP/1 bulk, and 1,217.2 to 1,772.8 for chunked bulk. HTTPS bulk changed
from 1,985.8 to 2,053.7 and HTTP/2 bulk from 4,270.5 to 4,527.2. Small multiplexed
HTTP/2 responses improved from 50.0 to 31.8. Small HTTP/1 p99 at saturation also
rose from approximately 0.74 ms to 1.25 ms. More readiness owners increase useful
parallelism but can also increase contention and scheduling work. The blocking
controls still outperform the candidate on these original streaming workloads.

All 112 uninstrumented native trials passed without generator-saturation or
incomplete-connection exclusions. The 20 additional Java 21 PLATFORM workload
pairs covered sequential requests, uploads, multipart, compression, pipelining
and HTTPS chunked output. Candidate/starting throughput ratios ranged from 1.008
for highly compressible HTTPS to 3.873 for HTTPS chunked output. These are single
runs: the small differences on compression and sequential HTTP/2 do not establish
a meaningful gain by themselves.

Java 25 AUTO uses virtual application and internal workers. Its separate
single-run comparison verified virtual application workers in both NIO versions
and the explicitly VIRTUAL earlier-blocking control:

| Java 25 workload, as above | Starting NIO AUTO | Candidate NIO AUTO | Earlier blocking VIRTUAL |
| --- | ---: | ---: | ---: |
| Small HTTP/1 | 73,364 | 214,777 | 246,990 |
| Fixed HTTP/1 bulk | 1,017 | 2,949 | 6,492 |
| Fixed HTTPS/1 bulk | 482 | 2,238 | 5,104 |
| HTTP/2 bulk | 320 | 1,695 | 2,837 |
| Chunked HTTP/1 bulk | 1,011 | 2,877 | 4,230 |
| Multiplexed HTTP/2 | 54,584 | 156,071 | 166,938 |

For small HTTP/1 responses, Java 25 CPU cost fell from 38.5 to 27.6 microseconds
per request and p99 from 0.724 to 0.508 ms. Explicit Java 21 VIRTUAL runs also
passed all six core workloads, including 215,854 small HTTP/1 responses/s and
1,712 HTTP/2 bulk responses/s. These fixture-specific worker-policy comparisons
remain separate from the repeated PLATFORM controls. Bulk transfers still show
substantial gaps to the earlier blocking implementation.

Both separate diagnostic JFR runs passed, peaking at approximately 685 and
696 MiB under the 1 GiB cgroup limit. Instrumentation substantially reduced
throughput; those rates are excluded from the performance comparisons.
Normalizing the byte-array allocation sample weights by completed responses in
each recording gives approximately 1,249,000 bytes per HTTP/2 bulk response for
starting NIO and 108,000 for the candidate. These are sampled allocation weights,
not retained heap. Recorded blocking-write park starts remain approximately
129 and 126 per bulk response: reusing encodings removes allocation pressure,
but the original application's 8 KiB write loop still imposes many handoffs.
Small HTTP/1 executor-idle park starts in the recorded leading sites fell from
approximately 5.0 to 3.9 per response. Park durations are clipped to their overlap
with the measurement window; profiles are not used as clean throughput results.

All 24 idle-capacity cases passed: one and 1,024 connections for HTTP/1, HTTPS/1
and TLS HTTP/2 on each runtime. Every target connection remained open throughout
the ten-second warm-up and thirty-second hold, then answered its final reuse
probe. The 1,024-connection cases consumed approximately 0.010–0.011 CPU cores
during the hold. Java platform-thread counts and process RSS after that hold:

| Runtime | HTTP/1 threads / MiB | HTTPS/1 threads / MiB | HTTP/2 threads / MiB |
| --- | ---: | ---: | ---: |
| Java 11 | 47 / 138.0 | 98 / 252.0 | 120 / 254.6 |
| Java 17 | 46 / 138.7 | 85 / 245.5 | 97 / 249.9 |
| Java 21 | 47 / 133.8 | 99 / 256.4 | 92 / 262.4 |
| Java 25 | 26 / 130.8 | 34 / 228.6 | 34 / 235.3 |

ThreadMXBean counts Java platform threads, excluding native GC/JIT workers.
Cached workers created during connection setup can remain alive through this
hold; these counts do not imply permanently assigned connection workers. RSS
includes JVM and setup allocations, so its difference from the one-connection
control is not an exact retained-memory cost per connection.

An additional Java 11 HTTPS run held 1,024 connections for ninety seconds after
the ten-second warm-up. Java platform threads fell from 126 to 26 as cached
workers expired; all 1,024 connections stayed open and answered their final
reuse probes. No request errors or resource violations were reported. This
checks idle worker retirement and connection retention, not long-term heap
reclamation.

All 27 additional write-granularity trials passed without generator or
connection exclusions. They use the same 1 MiB payload, eight connections and
Java 21 PLATFORM, with three separate fixture variants: one blocking write,
one `AsyncHandle.write` followed by `complete`, and the original 8 KiB loop using
the existing explicit `response.outputStream(65536)` option. Each variant
preallocates two additional immutable 1 MiB payload arrays in every control JVM.
Single-run throughput, in requests/s:

| Protocol / write mode | Starting NIO | Candidate NIO | Earlier blocking |
| --- | ---: | ---: | ---: |
| HTTP/1 / whole | 1,243 | 4,538 | 18,111 |
| HTTP/1 / async | 987 | 2,459 | 17,476 |
| HTTP/1 / buffered 64 KiB | 1,212 | 3,948 | 14,806 |
| HTTPS/1 / whole | 554 | 3,532 | 7,814 |
| HTTPS/1 / async | 466 | 1,813 | 7,628 |
| HTTPS/1 / buffered 64 KiB | 530 | 3,185 | 6,782 |
| HTTP/2 / whole | 388 | 1,517 | 2,059 |
| HTTP/2 / async | 329 | 1,117 | 1,974 |
| HTTP/2 / buffered 64 KiB | 370 | 1,330 | 2,024 |

Larger blocking writes reduce handoff costs: candidate CPU per response was
606 microseconds for whole HTTP/1 and 825 for whole HTTPS, compared with 1,712
and 2,054 in the original streaming workload. The async path still renders in
8 KiB source turns and drains bounded captures; one large public async write
does not translate into one transport submission. HTTP/2 whole and buffered
output spent 2,438 and 3,403 milliseconds in GC respectively during their
twenty-second measurements. A full 16 KiB DATA payload plus its nine-byte header
exceeds the current scratch-cache size; the following experiment measures that
allocation boundary separately. These diagnostic API variants do not replace
the primary streaming workload or change application buffer defaults.

All 24 matched-rate trials passed and achieved their requested aggregate rates
within 5%. These use h2load's per-client rate control, the original streaming
fixture and Java 21 PLATFORM. Each workload has two offered rates and one run
per source/rate. The higher rate in each pair gives:

| Workload / requested responses/s | CPU microseconds/response, starting → candidate | p99 ms, starting → candidate |
| --- | ---: | ---: |
| Small HTTP/1 / 50,000 | 42.1 → 54.6 | 0.849 → 1.009 |
| Multiplexed HTTP/2 / 40,000 | 51.1 → 33.9 | 1.022 → 0.741 |
| HTTPS bulk / 384 | 2,066.7 → 2,143.8 | 13.052 → 3.253 |
| HTTP/2 bulk / 256 | 4,407.5 → 4,072.3 | 22.668 → 5.464 |
| Fixed HTTP/1 bulk / 768 | 1,228.5 → 1,738.9 | 8.492 → 3.687 |
| Chunked HTTP/1 bulk / 768 | 1,268.2 → 1,800.1 | 8.598 → 3.816 |

Thus the greater maximum throughput does not erase the HTTP/1 CPU tradeoff at
matched load. Multiplexed HTTP/2 improves CPU and latency in these checks;
HTTPS bulk substantially improves latency at similar CPU cost. These exploratory
rate-controlled measurements are not an overload SLA or confidence intervals.
The report retains every raw trial, including the lower offered-rate cases.

Twelve further rate-controlled trials changed only the candidate's
`withNioThreads` setting to one or two. All passed and reached their requested
rates. CPU microseconds per response, including the four-owner controls above:

| Workload / requested responses/s | Starting NIO | Candidate, one owner | Two owners | Four owners |
| --- | ---: | ---: | ---: | ---: |
| Small HTTP/1 / 20,000 | 45.6 | 40.1 | 52.5 | 62.8 |
| Small HTTP/1 / 50,000 | 42.1 | 35.7 | 45.6 | 54.6 |
| HTTPS bulk / 384 | 2,066.7 | 1,555.1 | 1,825.5 | 2,143.8 |
| Multiplexed HTTP/2 / 40,000 | 51.1 | 33.8 | 33.9 | 33.9 |
| Fixed HTTP/1 bulk / 768 | 1,228.5 | 1,107.4 | 1,299.5 | 1,738.9 |
| Chunked HTTP/1 bulk / 768 | 1,268.2 | 1,120.4 | 1,315.8 | 1,800.1 |

The CPU increase on these HTTP/1 workloads is largely a parallelism tradeoff.
With one owner, the candidate uses less CPU than starting NIO at all six offered
loads. HTTPS bulk p99 is 6.464 ms with one owner, 5.436 with two and 3.253 with
four; small HTTP/1 at 50,000 responses/s gives 0.750, 0.690 and 1.009 ms
respectively. A single HTTP/2 connection does not use multiple readiness owners.
The default remains capped at four to retain the parallel-throughput gains;
`withNioThreads(1)` offers a measured lower-CPU configuration on this host. The
choice is workload-dependent, and these single-run controls do not establish a
universal optimum.

## Full DATA-frame encoding and async rendering (17 October 2026)

The preceding checkpoint is committed as `625e9f139f890ad67363f3e12573e44181a8fb04`.
Two further experiments keep its evidence intact and use separate immutable
source/class inventories.

The `full-frame-cache` snapshot has production SHA-256
`326d62731402e701348d5c04c94187880fb84678b8a5bd5b6d0bda6e5686e586`.
Its encoding leases include the nine-byte header of a default 16 KiB DATA frame.
The shared cache still holds at most 64 idle leases, adding 576 bytes of array
payload to its idle bound. Socket batches retain their 16 KiB flush boundary.
The selector wakeup check also uses exact thread identity, avoiding a call to a
caller thread subclass's `equals` implementation.

A regression test that cycles full DATA frames through the bounded cache failed
before the cache change and passed afterward; the 104 focused tests passed with
NullAway/Error Prone. An isolated ThreadMXBean allocation probe encoded 100,000
frames per repeat after 20,000 warm-up frames. The same probe class measured
16,416 bytes allocated per frame before the change, then zero in its final two
repeats afterward (208 bytes total in the first measured repeat). Payload
checksums matched. This isolates encoding allocation; its timing is not a server
throughput measurement.

Twelve native trials paired this snapshot with the preceding checkpoint in
opposite orders. All passed without generator or connection exclusions. For
whole-write HTTP/2 bulk, median throughput increased from 1,534 to 1,720
responses/s, CPU fell from 3,158 to 3,044 microseconds/response, and GC time fell
from 2,286 to 761 milliseconds per twenty-second measurement. The original
8 KiB HTTP/2 bulk and multiplexed workloads were approximately flat: 1,214 to
1,197 and 138,089 to 137,721 responses/s respectively. Two repeats do not establish
whether those small differences are meaningful.

The next snapshot, `async-turn`, has production SHA-256
`859d92fd5af7d436a357e9161cbe8065aac7eaf08f82940eb85af4e950477e15`.
It increases channel async rendering input and drain chunks from 8 to 16 KiB,
matching a default HTTP/2 DATA payload. Captured output still spills beyond
32 KiB and the renderer yields after 64 immediate steps. Direct/read-only source
scratch and spill-file read scratch increase from 8 to 16 KiB; ordinary active
captures can also retain a larger chunk. Transport acknowledgement still gates
source reuse, failure publication and capture deletion. The focused 155-test
renderer, storage, async-response, HTTP/2 and TLS suite passed.

Twelve native trials compare 8 and 16 KiB renderer turns on top of the same
full-frame cache, in opposite orders. All passed with no generator or connection
exclusions. The fixture performs one async write of a 1 MiB body, with eight
connections and Java 21 PLATFORM. Medians of two runs:

| Protocol | 8 KiB turns, responses/s | 16 KiB turns, responses/s | CPU microseconds/response, 8 → 16 KiB |
| --- | ---: | ---: | ---: |
| HTTP/1 | 2,418 | 2,941 | 1,897 → 1,328 |
| HTTPS/1 | 1,822 | 2,350 | 2,301 → 1,609 |
| TLS HTTP/2 | 1,106 | 1,508 | 4,666 → 3,250 |

These increases are approximately 22%, 29% and 36%, with about 30% less CPU per
response. Observed candidate peak cgroup memory stayed below 137 MiB in these
trials. That observation does not erase the larger per-write scratch bound or
predict memory use with many stalled application writes.

The final snapshot passed clean `verify` on Java 11, 17, 21 and 25. Each run
discovered 5,289 tests with zero failures and errors; Java 11 and 17 skipped the
176 virtual-thread-only cases, while Java 21 and 25 skipped none. Java 21 also
passed NullAway/Error Prone. Its clean verified production classes are byte for
byte identical to the frozen classes used for the native measurements.
The external functional matrix passed all 140 cases, and the four transfer-pressure
runs passed concurrent uploads/downloads, cancellation with connection reuse,
and shutdown during transfer. On each runtime, h2spec again passed all 147 cases;
Autobahn reported 244 passes and the same three informational cases. Their
reviewed behavior and close details match the earlier reports. The strict
independent-harness exit of one remains recorded alongside the separate passing
comparison review.

Six sustained native checks then exercised one async write per 1 MiB response,
with eight persistent clients, ten seconds of warm-up and 120 seconds of measured
load per check. AUTO selected platform application workers on Java 11 and virtual
workers on Java 25. All passed with zero request errors and no generator or
connection exclusions:

| Runtime | Protocol | Measured responses | Peak cgroup memory, MiB |
| --- | --- | ---: | ---: |
| Java 11 | HTTP/1 | 362,696 | 99.6 |
| Java 11 | HTTPS/1 | 233,510 | 106.4 |
| Java 11 | TLS HTTP/2 | 161,601 | 121.5 |
| Java 25 | HTTP/1 | 374,460 | 94.1 |
| Java 25 | HTTPS/1 | 287,440 | 117.5 |
| Java 25 | TLS HTTP/2 | 205,324 | 137.6 |

That is 1,625,031 measured large responses under the same 512 MiB heap and 1 GiB
cgroup limits. Payload hashes matched before and after each run; successful
statuses and request counts were reconciled during load. These checks cover the
changed async body path for bounded runs, not multi-day leak behavior. The final
evidence audit passed all 164 checks, including source, test, dependency and
compiled-class inventories. Its 36 native trials include the paired cache and
renderer experiments, six final original-workload checks and these six sustained
runs; the earlier checkpoint's separate evidence remains intact.

## Borrowed transport drains (17 October 2026)

`TransportOutputBuffer` previously retained the complete asynchronous source
until acknowledgement while also copying each prefix into its ring. The async
path now passes a bounded view of that same retained source to the transport
sink. This removes one full plaintext copy at this layer, including frame bytes;
it does not remove HTTP/2 frame encoding, response capture or TLS encryption.
The blocking bridge continues to own its ring copies.

Each call is still capped by both the configured output capacity and the drain
budget. Channel connections retain the existing 8,201-byte limit and transport
turn count. No buffer or queue was enlarged; the ring allocation remains present,
so this change makes no claim of lower retained heap. The same single source is
borrowed for the same lifetime. A separate view preserves the caller's position,
limit and mark. Abort takes the drain lock before returning source ownership,
and completion/progress callbacks still run outside that lock.

TLS wraps a duplicate without retaining its source between calls. A queued
plaintext prefix advances only when the corresponding ciphertext has completely
drained. Retrying with a smaller budget can acknowledge part of that prefix;
retrying with a larger budget cannot acknowledge a later record. New TLS 1.2/1.3
tests stall the final ciphertext byte, vary plaintext budgets from one byte to
larger than the capacity, and check exact output for heap and direct read-only
sources. Transport tests also verify shared backing storage, sliced offsets,
zero/partial writes, safe reuse after completion, concurrent abort and a sink
that advances its view before failing.

Caller review covered HTTP/1 captured output, the public blocking stream adapter,
HTTP/2 owned frame encodings and WebSocket payloads. Their existing completion
boundaries retain source storage through the borrow; failed HTTP/2 encodings are
discarded without pooling while a transport might still reference them.

Validation logs and XML reports are retained under
`../nonblocking-validation/borrowed-output-20261017/`. The earlier three-way
baseline keeps its frozen production classes. No throughput claim is made from
this change or from tests run while the host is in use. A controlled comparison
must measure this candidate separately against the preserved baseline.

The focused 214-case transport/protocol suite passed on Java 11, 17 and 21 with
zero failures, errors or skips. Full Maven verification passed all 5,295 tests
on both Java 21 and 25, again with zero failures, errors or skips; Java 21 also
passed NullAway/Error Prone. The saved-log/XML/source reconciliation passed all
756 checks. The full Java 11/17 suites and external conformance campaigns were
not rerun for this change.

## Borrowed async response captures (17 October 2026)

An unencoded asynchronous response previously copied every rendered body chunk
into capture storage even when the built-in stream passed the supplied array
range through unchanged. The renderer now offers its stable input range to the
capture. The capture may retain that range only when its sole nonempty array
write has the same array identity, offset and length. Stream initialization,
headers and framing still run through the existing output stream chain.

Borrowing is enabled only after response initialization selects no content
encoder. A custom encoder can emit its input unchanged and then immediately
reuse it, so even such an encoder must keep owned capture storage. A buffered
prefix, different array/range or additional write also takes the copying path;
a second write materializes the borrowed prefix before appending or spilling.
An offer without a write emits nothing. Buffered output therefore remains free
to combine, split or retain writes without exposing its storage to a later
render turn.

Each borrow is at most 16 KiB. The renderer already retains the source and waits
for actual transport/frame acknowledgement before the next turn, including
failure and continuation rejection. That same boundary now also protects the
capture's input range and direct/read-only input scratch. No transport, TLS,
flow-control, queue-size or turn-limit change accompanies this optimization.
The initial 256-byte capture buffer is still allocated; eligible turns avoid
its larger replacement array and body copy. The 32 KiB owned-capture spill bound
and 16 KiB input, drain and file-scratch bounds remain unchanged.

New regression coverage checks sliced array offsets, direct/read-only input,
delayed acknowledgements, rejection, prefix/suffix fallback and spill failure.
Real HTTP/1 and HTTP/2 responses over cleartext, TLS 1.2 and TLS 1.3 also exercise
an encoder that forwards then overwrites its scratch input. Stalled HTTP/1
identity responses now declare their content length to exercise borrowing
through backpressure and cancellation; gzip and expanding-encoder cases retain
their previous framing. HTTP/2 coverage includes stalled flow control.

Evidence is retained in
`../nonblocking-validation/borrowed-capture-20261017/`. The before/after native
fixture performs one asynchronous write per 1 MiB response, with eight clients;
this is a different application workload from the earlier 8 KiB blocking-stream
comparison. Both editions use identical fixture source and dependencies, compiled
with Java 21 targeting Java 11. The before build is `bfa03eb1`; the candidate
production source SHA-256 is
`1aaf7ca526bae01815f460e1d8b5558a37f55ce63caafb4472b9d15208966d26`.
Only the eleven class files belonging to the seven changed production source
files differ. The immutable snapshots preserve the exact tested bytecode.

A ThreadMXBean probe runs the same compiled renderer/capture probe against each
edition, using a preallocated 1 MiB input, immediate acknowledgements and a
checksum over every output byte. After 500 warm-up responses, each of three
repeats renders 200 responses. Median allocation falls from 1,076,520 to 27,944
bytes per response: one MiB less allocation at this layer. This excludes network,
framing and TLS allocation and does not measure server throughput or retained
heap. The same checksum passed every repeat.

The busy-PC native indicator uses two reversed-order pairs per workload, fresh
Java 21 PLATFORM JVMs, two seconds of warm-up and four seconds of measurement,
512 MiB heaps and 1 GiB cgroups with swap disabled. The chunked control also uses
1 MiB bodies and eight clients; gzip uses 64 KiB input bodies and eight clients.
The unchanged small-response control uses 128-byte bodies and 32 streams on one
HTTP/2 connection. Latency is closed-loop native-generator request latency.
Medians of two trials:

| Workload | Before → after, responses/s | Throughput change | CPU/response change |
| --- | ---: | ---: | ---: |
| HTTP/1 fixed async | 2,361 → 3,226 | +36.6% | -18.4% |
| HTTPS/1 fixed async | 1,800 → 2,398 | +33.2% | -19.2% |
| TLS HTTP/2 async | 1,218 → 1,498 | +22.9% | -10.1% |
| HTTP/1 chunked control | 1,650 → 1,550 | -6.1% | +6.5% |
| Small multiplexed HTTP/2 control | 117,993 → 115,708 | -1.9% | +0.3% |
| HTTPS/1 gzip control | 15,797 → 15,817 | +0.1% | -1.8% |

All 24 trials passed: 2,248,122 measured responses, zero errors, matching pre/post
payload hashes, reconciled timed statuses, no generator or connection flags,
clean service exits and no leftover response files. The three fixed-length
async workloads improved in both pairs; their p95 latencies fell approximately
26%, 24% and 19% respectively. These are indicative measurements on an occupied
workstation, not a controlled ranking against Mu3 or Mu4 blocking.

The initial chunked slowdown prompted six additional trials, with five seconds
of warm-up and six seconds measured. Their three paired throughput changes were
−4.5%, +3.9% and +10.5%; the median comparison was +7.0%, reversing the first
indicator. All 56,406 responses passed. This variability leaves the chunked
performance effect unresolved; it does not establish either a gain or a
regression. Gzip and small HTTP/2 controls likewise showed no consistent gain.
The raw results of both runs are preserved rather than selecting the favorable
chunked result.

Final validation passed all 5,321 tests in clean Maven `verify` builds on Java
21 and 25, with zero failures, errors or skips. Java 21 also passed NullAway and
Error Prone, and its verified production classes exactly match the frozen
classes used for measurement. The focused 146-case suite passed on Java 11,
17 and 21 with no failures, errors or skips. Logs, XML reports, source and
bytecode inventories, all 30 native trials and the allocation probe reconcile
in the final evidence audit. Full Java 11/17 suites and the separate external
conformance campaigns were not rerun for this change.
