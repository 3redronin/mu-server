# mu-server developer guide (mu3 branch)

A field guide for developers picking up the `mu3` rewrite of mu-server. This is **not** the user-facing
documentation (see `README.md` and <https://muserver.io/> for that) — this document is about the internals:
how the code is organised, how requests flow through it, and where to start when you make changes.

> Branch: `mu3`. Maven artifact: `io.muserver:mu3:0.0.3-SNAPSHOT`. The `mu3` rewrite drops the Netty/NIO
> backend used in earlier versions and replaces it with a blocking-IO-on-virtual-threads model.

---

## 1. Big picture

mu-server is an embeddable HTTP/1.1, HTTP/2, and WebSocket server. The `mu3` branch is a ground-up rewrite
that targets **JDK 11+ but is designed for JDK 21+ virtual threads**. The architectural change relative to
earlier versions:

| Aspect              | mu (legacy)                  | mu3 (this branch)                              |
|---------------------|------------------------------|------------------------------------------------|
| IO model            | Netty NIO event loops        | One blocking thread per connection             |
| Threading           | Pinned event-loop callbacks  | Virtual threads (one per request, when on 21+) |
| HTTP/2              | Netty's codec                | Hand-written parser/encoder + HPACK            |
| JAX-RS              | Same package, on top of NIO  | Same JAX-RS API, on top of the new core        |

The headline trade-off: simpler, more debuggable code in exchange for a hard dependency on JDK 21+ virtual
threads to scale. On older JVMs the server still works, but each connection consumes a platform thread, so
high concurrency suffers.

## 2. Building & running tests

```bash
mvn -q -pl . verify              # build + run all tests (the CI default)
mvn -q test -Dtest=MuServerTest  # one test class
mvn -q test -Dtest='RFC9113_*'   # one RFC test family
```

Java versions: CI runs against JDK **11, 17, 21, 25** (`.github/workflows/ci.yaml`). The `pom.xml` pins
`jdk.version=11` and the compiler enforces `-Werror`. If you add code that requires Java 17+ APIs,
guard it with reflection (see `MuServerBuilder.defaultExecutor()` for the pattern used for virtual
threads).

There are also `mvn` test runners in `src/test/java`:
- `RunLocal.java` — boots the server with a sample handler for ad-hoc browser testing
- `RunBasicAuth.java`, `Sniffer.java`, `ManyFiles.java`, `ThreadTesting.java` — focused experiments

## 3. Source layout

```
src/main/java/io/muserver/                   ← core server, ~130 files
    Mu3ServerImpl.java        ← the running server (see §5)
    MuServerBuilder.java      ← public entry point
    ConnectionAcceptor.java   ← accept loop + protocol selection
    Http1Connection.java      ← HTTP/1.x state machine
    Http1MessageParser.java   ← byte-level HTTP/1.x parser
    Http1Response.java
    Http2Connection.java      ← HTTP/2 connection (read loop + write loop)
    Http2Stream.java          ← per-stream state
    Http2Handshaker.java      ← preface + initial SETTINGS exchange
    Http2*Frame*.java         ← frame types (DATA, HEADERS, SETTINGS, ...)
    Field{Block,Line}*.java   ← unified header model used by both protocols
    HpackTable / Huffman*     ← HPACK (RFC 7541) implementation
    Mu3Request.java / BaseResponse.java  ← user-facing per-request model
    Routes.java               ← URI templates → handler dispatch
    handlers/                 ← built-in MuHandlers (CORS, CSRF, static, ...)
    rest/                     ← JAX-RS implementation (~70 files)
    openapi/                  ← OpenAPI 3 schema model + writer
    package-info.java         ← @NullMarked (jspecify)

src/test/java/
    io/muserver/              ← unit + integration tests
        RFC7541_*Test.java    ← HPACK conformance
        RFC9113_*Test.java    ← HTTP/2 conformance
        handlers/, openapi/, rest/
    scaffolding/              ← test helpers (ServerUtils, ClientUtils, RawClient...)
    DocumentationExamples.java, RunLocal.java, ...   ← runnable sandboxes
```

Everything in `io.muserver` is package-private by default. Only types intended for users are `public`.
That convention is load-bearing: keep impl classes package-private so the public API stays small. The
package is `@NullMarked` (jspecify), so any field/parameter that may be null must carry `@Nullable`.

## 4. Reading the code: suggested order

If you've never touched mu3 before, read in this order:

1. **`MuHandler.java`** — 26 lines. The whole user contract is `boolean handle(req, resp)`.
2. **`MuServerBuilder.java` → `start()`** — and follow into `Mu3ServerImpl.start(builder)` which wires
   handlers, encoders, executor, and creates one `ConnectionAcceptor` per port.
3. **`ConnectionAcceptor.acceptLoop()`** (line 94) — the classic "accept; submit to executor; recur" loop.
   `handleClientSocket()` does TLS handshaking + ALPN, sniffs cleartext h2 prior knowledge, then dispatches
   to either `Http1Connection` or `Http2Connection`.
4. **`Http1Connection.start()`** (line 36) — read-request → run-handlers → write-response, looping on the
   same socket until close. Easiest connection class to grok.
5. **`Http2Connection.start()`** (line 317) — handshake, then a frame-dispatch read loop with a separate
   write loop (`startWriteLoop`) running on the executor. Stateful and worth a close read.
6. **`BaseHttpConnection.handleExchange()`** (line 67) — runs the handler chain; both connection types
   funnel through here.
7. **`Routes.route(...)`** — how URI templates are turned into a `MuHandler`. Backed by `rest/UriPattern`.
8. **`rest/`** — only relevant if you touch JAX-RS. Entry: `RestHandlerBuilder` → `RestHandler`.

After that, the per-feature packages (`handlers/`, `openapi/`, encoders, websockets) are leaf-shaped — you
can read them in isolation.

## 5. Server lifecycle

```
MuServerBuilder
   │
   └─► Mu3ServerImpl.start(builder)
         │
         ├─ creates an ExecutorService (virtual-thread-per-task on JDK 21+, cached pool otherwise)
         ├─ inserts RequestVerifierHandler + ExpectContinueHandler at the front of the chain
         ├─ for each port (https first, then http), creates a ConnectionAcceptor:
         │     ServerSocket + acceptor thread + idle-timeout watcher thread
         │     (HTTPS path also reads HttpsConfig; HTTP path may sniff the h2 preface)
         └─ acceptor.start() spawns the accept loop
```

**Threading model:**

- One **acceptor thread** per port (platform thread, daemon=false).
- One **timeout watcher thread** per port (only if `idleTimeoutMillis > 0`).
- The configured **executor** runs everything else: per-connection handling, HTTP/2 write loop,
  request handler bodies. By default this is `Executors.newVirtualThreadPerTaskExecutor()` on JDK 21+.
- HTTP/2 uses an extra `Future<?>` for the **write loop** so reads and writes don't block each other.
- A `ScheduledExecutorService` exists (`OffloadingScheduledExecutorService`) that submits scheduled tasks
  back to the main executor, so timers don't pin platform threads either.

If you change anything in the connection classes, remember: **the executor may be a virtual-thread
executor**. Synchronized blocks pin carrier threads — prefer `ReentrantLock` (which is what
`Http2Connection` already uses for its write queue).

## 6. How HTTP/1.x is handled

| Concern             | Class                                                              |
|---------------------|--------------------------------------------------------------------|
| Connection state    | `Http1Connection` (extends `BaseHttpConnection`)                   |
| Wire-format parser  | `Http1MessageParser` (request line, headers, chunks, trailers)     |
| Body input stream   | `Http1BodyStream` wraps the parser for handler-side reads          |
| Response framing    | `Http1Response` + `ChunkedOutputStream` / `FixedSizeOutputStream` / `CloseDelimitedOutputStream` |
| Header model        | `FieldBlock` (shared with HTTP/2)                                  |

The flow inside `Http1Connection.start()`:

1. Allocate a single `Http1MessageParser` for the lifetime of the connection.
2. Call `requestParser.readNext()` until it returns `EOFMsg`. The parser produces `HttpRequestTemp`
   objects (header section parsed, body still pending) and pushes incremental body bits as the handler
   reads through `Http1BodyStream`.
3. Build a `Mu3Request`, attach it to a fresh `Http1Response`, and run the handler chain via
   `BaseHttpConnection.handleExchange()`.
4. After the response, `cleanUpNicely()` decides whether to close the socket (any of: client/server asked
   for `Connection: close`, response had to be close-delimited, body wasn't fully drained).
5. Pipelined requests share the same parser instance, so request N+1 can already be buffered.

WebSocket upgrade: if the handler attached a `WebsocketConnection` to the response, the loop hands the
socket to the websocket implementation (`websocket.runAndBlockUntilDone`) and exits.

HTTP/1.0 is recognised; chunked transfer encoding is rejected for 1.0 (see the recent `Add HTTP/1.0
request policy tests and chunked rejection` commits).

## 7. How HTTP/2 is handled

HTTP/2 lives in roughly a dozen files. The key ones:

| Concern                       | Class                                                       |
|-------------------------------|-------------------------------------------------------------|
| Protocol selection            | ALPN in `ConnectionAcceptor.handleClientSocket()`; cleartext "prior knowledge" sniff in `sniffClearTextHttpVersion()` |
| Connection preface + SETTINGS | `Http2Handshaker`                                           |
| Connection state machine      | `Http2Connection` (read loop + lock-based write loop)       |
| Per-stream state              | `Http2Stream`                                               |
| Frames                        | `Http2FrameHeader`, `Http2DataFrame`, `Http2HeadersFrame`, `Http2ContinuationFrame`, `Http2Settings`, `Http2Ping`, `Http2GoAway`, `Http2WindowUpdate`, `Http2ResetStreamFrame`, `Http2FrameType` |
| Flow control                  | `Http2IncomingFlowController`, `Http2OutgoingFlowController`, `CreditAvailableListener` |
| HPACK                         | `HpackTable`, `FieldBlockEncoder`, `FieldBlockDecoder`, `HuffmanEncoder`, `HuffmanDecoder` |
| Request/response              | `Mu3Request` + `Http2Response` + `Http2BodyInputStream` + `Http2DataFrameOutputStream` |

The runtime split inside `Http2Connection`:

```
[read loop, in start()]                        [write loop, started via startWriteLoop()]
   read FRAME_HEADER + payload                    take WriteTask off the queue
   dispatch by frame type:                        check connection-level flow-control credit
     HEADERS  → readHeaders → new Http2Stream     check stream-level credit
     DATA     → readDataFrame                     write the frame, flush, complete the WriteTask
     SETTINGS → readSettingsFrame                 wait on writeQueueCondition for more work
     PING/WINDOW_UPDATE/GOAWAY/RST_STREAM/PRIORITY
```

The two loops communicate through `writeQueue` (an `ArrayDeque`) guarded by `writeQueueLock`
(`ReentrantLock` + `Condition`). All writes — headers, data, settings, control frames — go through
`write(LogicalHttp2Frame)` so flow-control accounting and ordering are centralised.

State is split deliberately into `readState` and `writeState` (both `HState` enums) so the server can keep
draining outbound frames after the peer has stopped sending. Read `markLocalShutdownInitiatedLocked()`
and `drainWritableFramesLocked()` together to understand graceful shutdown.

Things that are **deliberately not implemented** (see `HTTP2DO.md`): Server Push (§8.4), the `CONNECT`
method (§8.5), `h2c` upgrade (§8.6 — only ALPN h2 and prior-knowledge cleartext h2 are accepted).

## 8. Headers: one model, two protocols

`FieldBlock` is a list of `FieldLine` (a `(HeaderString name, HeaderString value)` pair). Both HTTP/1.x
and HTTP/2 produce and consume it. `HeaderString` interns common header names, distinguishes between
"header" and "value" types, and has a `writeAsHttp1(OutputStream)` method on `FieldBlock` for h1 and an
HPACK-encoded path for h2.

Pseudo-headers (`:status`, `:method`, `:path`, `:authority`, `:scheme`) are stored as `FieldLine` with the
leading colon — see `HeaderNames.PSEUDO_STATUS` etc. They are filtered out before being exposed to user
code, but you'll see them in the encoder/decoder tests.

If you add new header validation, do it in `FieldBlock` so both protocols benefit. RFC 9113 §8.2 field
validity is enforced here.

## 9. Routing & handler chain

The `MuHandler` chain runs in registration order. The first to return `true` ends the chain. By default
`Mu3ServerImpl.start()` prepends two synthetic handlers:

- `RequestVerifierHandler.INSTANCE` — sanity-checks the request before user handlers see it.
- `ExpectContinueHandler` — handles `Expect: 100-continue` if `withAutoHandleExpectContinue(true)` (the default).

`Routes.route(method, uriTemplate, RouteHandler)` is the typed convenience layer. URI templates support
`{name}` and `{name : regex}` and are compiled by `rest/UriPattern` into a regex with named groups.

Adding a handler that needs to short-circuit subsequent handlers? Return `true`. Adding a filter that
wants to log/decorate but let downstream handlers run? Return `false` and don't write to the response.
Throwing causes a 500 unless the handler chain has been turned async — see `MuRequest.handleAsync()` and
`Mu3AsyncHandleImpl`.

## 10. Tests

230+ test files. They split into three rough buckets:

**Unit tests** — exercise one class with no live socket. Examples: `Http1MessageParserTest`,
`HpackTableTest`, `HuffmanDecoderTest`, `FieldBlockDecoderTest`, `HeadersTest`. Fast.

**Integration tests** — boot a real server on `port 0`, hit it with okhttp / Jetty / a raw socket
client (`scaffolding.RawClient`), assert on the response. Examples: `MuServerTest`, `WebSocketsTest`,
`StreamingTest`, `ResponseStreamTest`.

**RFC conformance tests** — named after the RFC section they cover. `RFC7541_*` covers HPACK,
`RFC9113_*` covers HTTP/2. These intentionally use raw byte sequences so they can verify wire-level
behaviour. When you add HTTP/2 support, write the conformance test before the implementation — the file
naming convention will tell you where it belongs. The matching tracker is `HTTP2DO.md`.

**Cross-protocol parameterisation:** many tests use JUnit 5 `@ParameterizedTest` with
`@ArgumentsSource(scaffolding.ServerTypeArgs.class)`, which expands to three runs per test:
`http`, `https` (HTTP/1.1 only), and `h2`. Use this for any new test that should be protocol-agnostic.
The matching test setup goes through `scaffolding.ServerUtils.httpsServerForTest(protocol)`.

**Test scaffolding** lives in `src/test/java/scaffolding/`:
- `ServerUtils` — server builder per protocol
- `ClientUtils` — pre-configured okhttp client + a TLS trust-everything `SSLSocketFactory`
- `RawClient` / `Http1Client` / `H2Client` — for tests that need wire-level control
- `MuAssert` — `assertEventually(...)` style helpers
- `SlowBodySender`, `TestSseClient`, `SSLSocketFactoryWrapper`, etc.

Class-level `@Timeout(20)` on `MuServerTest` is the convention for integration tests; copy it.

## 11. Java features in use

- **Virtual threads** (JDK 21) — discovered reflectively in `MuServerBuilder.defaultExecutor()` so the
  artifact still runs on JDK 11. If you add JDK 21+ APIs, follow that pattern or guard with
  `Runtime.version()`.
- **`var`** (JDK 11) — used freely.
- **Switch on enums** in the HTTP/2 read loop (classic switch, not switch expressions, to keep JDK 11
  compatibility).
- **Try-with-resources** for sockets/streams.
- **`ConcurrentHashMap` / `ConcurrentLinkedQueue` / `AtomicReference`** for cross-thread state.
- **`ReentrantLock` + `Condition`** in `Http2Connection` (intentionally — `synchronized` would pin
  carriers under virtual threads).
- **jspecify `@NullMarked` / `@Nullable`** package-wide. The package-info opts in.
- **JAX-RS 3 (`jakarta.ws.rs`)** — `rest/` implements just enough of it to support the documented surface.
- **SLF4J 2** for logging. Tests use `slf4j-simple`.

## 12. Things to keep in mind when changing code

1. **Don't introduce `synchronized` on the IO path.** Use `ReentrantLock`. Synchronized methods pin the
   carrier thread when the executor is virtual-thread-based.
2. **Don't allocate large buffers per request.** `Http1MessageParser` reuses an 8 KB `readBuffer`;
   `Http2Connection` reuses one `ByteBuffer` sized to `maxFrameSize`. Follow the precedent.
3. **Headers are case-insensitive but order-preserving.** `FieldBlock` keeps insertion order; `HeaderString`
   handles the interning. Don't replace it with a `Map`.
4. **Use the existing `WriteTask` queue for HTTP/2 writes.** Bypassing it breaks flow control and ordering.
5. **Public API is whatever is `public` in `io.muserver`.** Removing or changing those is a breaking
   change — track it in `BREAKINGCHANGES.md`.
6. **Tests that bind ports must use `withHttpPort(0)` / `withHttpsPort(0)`** to avoid clashes; the
   helpers in `ServerUtils` do this for you.
7. **When the parser sees an oversize URL or header section, throw a `HttpException`.** Look at the
   existing `HttpException.requestTimeout()` etc. constants to see how rejected requests propagate.

## 13. Likely places to optimise (low-hanging fruit + bigger projects)

These are observations from reading the code, not committed work. Verify before acting.

**Quick wins**

- `Http2Connection.drainWritableFramesLocked()` (line 276) currently logs at `INFO` per frame
  (`log.info("frame=...")`, `log.info("Writing ...")`). At high throughput this is one log line per
  HTTP/2 frame — drop to `TRACE` or remove. Same for the `log.info("read fh = ...")` in the read loop.
- `Http1MessageParser` uses a `ByteArrayOutputStream` (line 45) for header accumulation. Pre-sizing it,
  or replacing it with a recycled `byte[]` keyed off `maxHeadersLength`, would skip the auto-grow copies.
- `FieldBlock.get()` / `getAll()` are O(n) linear scans every call; the same block is queried many times
  per request. A lazily-built `Map<HeaderString, List<FieldLine>>` would help on header-heavy paths
  (CORS, JAX-RS reflection). Watch out for ordering invariants.
- `serverUnavailableResponse` and similar fixed bytes are already cached — apply the same pattern to
  small responses generated by `RequestVerifierHandler` / error paths.
- Date headers are formatted per response (`Mutils.toHttpDate(new Date())`) — caching the formatted
  string for one second is a classic server-side optimisation and is safe given `date` second-precision.

**Medium**

- The HTTP/1 parser's `state` machine reads one byte at a time in places. Bulk-scanning for `\r\n` with
  `Arrays.mismatch` or a vectorised search would reduce per-byte branch cost — measurable on small
  pipelined requests.
- `Http2OutgoingFlowController.waitUntilAvailable(...)` polls per-stream and per-connection separately
  in `Http2Stream.waitUntilWritableDataCreditAvailable()`. A combined waiter (single `Condition` shared
  across streams + connection) would avoid spurious wakeups when only the other side has credit.
- `ConnectionAcceptor.timeoutLoop()` walks every connection every 200ms. A min-heap keyed on `lastIO`
  would scale better with thousands of connections.

**Bigger projects**

- **TLS without a thread per handshake.** `handleClientSocket` does the handshake on the executor thread
  for the connection's lifetime, which is fine for virtual threads but expensive on platform threads.
  An async handshake stage (kicking off only after ALPN settles) would reduce idle thread cost on JDK 11–17.
- **Zero-copy file responses.** `handlers/ResourceHandler` reads then writes through `OutputStream`s.
  Going through `Files.copy` to a `socket.getChannel()` (where available) would let static-file serving
  skip user-space copies. Watch out for TLS, where zero-copy isn't possible.
- **HTTP/2 priority (RFC 9113 §5.3.2).** Currently parsed but mostly ignored — implementing PRIORITY
  hints would fix the `[~]` entries in `HTTP2DO.md`.
- **WebSocket `permessage-deflate`** — listed in `BREAKINGCHANGES.md` as a Mu 3 todo.
- **Connection reuse and TLS feature checks (RFC 9113 §9.1.1, §9.2)** — also `[ ]` in the tracker.

## 14. Useful entry points for spelunking

| Question                                            | Start here                                       |
|-----------------------------------------------------|--------------------------------------------------|
| "How does a request reach my handler?"              | `ConnectionAcceptor.handleRequest` → `Http1Connection.start` / `Http2Connection.start` → `BaseHttpConnection.handleExchange` |
| "Why is my response chunked / fixed length / closed?" | `Http1Response.outputStream()` line 54         |
| "Where is the HPACK table?"                         | `HpackTable.java`, exercised by `RFC7541_*Test`  |
| "How does the server stop?"                         | `Mu3ServerImpl.stop` → `ConnectionAcceptor.stop` → `shutdownConnections()` |
| "How is HTTP/2 graceful shutdown sequenced?"        | `Http2Connection.markLocalShutdownInitiatedLocked` (sends warning GOAWAY then drains) |
| "Where are URI templates compiled?"                 | `rest/UriPattern.uriTemplateToRegex`             |
| "How does JAX-RS hook in?"                          | `rest/RestHandler` + `rest/RestHandlerBuilder`   |
| "How are stats exposed?"                            | `Mu3StatsImpl`, surfaced via `MuServer.stats()`  |
| "How does idle timeout work?"                       | `ConnectionAcceptor.timeoutLoop` + `BaseHttpConnection.lastIO` |

## 15. Conventions cheat sheet

- New impl class? Make it package-private. Public only for new user-facing types.
- New configurable knob? Add a `withXxx()` to `MuServerBuilder`, expose it on `MuServer` if observable.
- New exception? Extend `MuException` (runtime) or `HttpException` (mappable to a status).
- New protocol-agnostic test? Use `@ParameterizedTest` + `@ArgumentsSource(ServerTypeArgs.class)`.
- New RFC behaviour? Put the test in `RFC<doc>_<section>_<name>Test.java` and update `HTTP2DO.md`.
- Logging: use SLF4J. `INFO` for connection lifecycle events, `DEBUG` for handshake/protocol details,
  `TRACE` or removed for per-frame chatter.
- Nullness: this package is `@NullMarked`. Annotate optional fields/parameters with `@Nullable` from
  `org.jspecify.annotations`.

---

If something here is wrong or out of date, fix it — the source is the source of truth and this document
should track it.
