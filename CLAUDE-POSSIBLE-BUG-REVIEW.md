# Possible bugs and edge-case issues — mu4 branch

Findings from a focused read of the HTTP/1.x and HTTP/2 implementations on the `mu4` branch
(`src/main/java/io/muserver/`). Each item lists the file, line(s), the issue, why it matters, and
a suggested direction. **Severity** is a rough subjective rating — please verify with a test
before fixing, especially for the latent items.

References: line numbers are at the time of writing (commit `faa3e5f6`).

## Quick summary

### Likely real bugs (HIGH):

- A1 — ChunkedOutputStream.write(int) writes raw byte 0x01 instead of ASCII '1' for the chunk size, producing invalid wire bytes on the rare single-byte path.
- B1 — When Http2FrameHeader.readFrom throws a stream-level FRAME_SIZE_ERROR, the unread payload bytes stay on the wire; the next iteration of the read loop reads garbage. Should be a connection error.
- B2 — Http2Stream.state is non-volatile and mutated by both the read loop and the request handler thread (memory-visibility race; flagged by existing TODO comments at lines 161 and 393).

### Notable medium-severity:

- A2 — Long.parseLong(..., 16) for chunk sizes is uncaught; oversized hex chunk size becomes a generic "internal error" log.
- A3 — Between pipelined keep-alive requests, setSoTimeout(0) is set, so the documented requestIdleTimeoutMillis doesn't bound idle time.
- B5 — :authority is parsed but the existing host header wins when both are present, contrary to RFC 9113 §8.3.1.
- B8 — Unbounded CONTINUATION fragment accumulation before HPACK decode (CVE-2024-27316 class DoS).
- B18 — Http2Connection.startRequest lets RejectedExecutionException kill the read loop instead of REFUSED_STREAM-ing the new stream.

### Performance/cleanup:
C
- B7 — Per-DATA-frame byte[] allocation.
- B13/B14 — log.info per frame on the hot path; leftover log.info("hmmm") in both flow controllers.
- C3 — FieldBlock is O(n) on every header lookup.
- C4 — Date header formatted per response.

---

## A. HTTP/1.1

### A1. `ChunkedOutputStream.write(int)` writes invalid chunked encoding — **HIGH**

`ChunkedOutputStream.java:17-28`

```java
@Override
public void write(int b) throws IOException {
    var array = new byte[6];
    array[0] = 1;       // ← raw byte 0x01, NOT ASCII '1' (0x31)
    array[1] = 13;
    array[2] = 10;
    array[3] = (byte)b;
    array[4] = 13;
    array[5] = 10;
    out.write(array);
}
```

The first byte is supposed to be the chunk size in hex ASCII. Sending `0x01` instead of `'1'`
produces a wire sequence the client will treat as a chunk-size parse error — most HTTP clients
will close the connection with a protocol error.

**Impact:** Any code path that writes to a chunked response one byte at a time (e.g.,
`Writer.write(int)` underneath, `PrintStream` interactions, frameworks that defensively call the
single-byte form) will corrupt the stream. The `byte[]`/`int,int` overloads are correct, so this
is only triggered on the rare single-byte path.

**Fix:** Use the ASCII byte for `'1'` (0x31), or reuse the same hex-encoding logic as the
multi-byte path:

```java
out.write((byte)'1');
out.write(CRLF, 0, 2);
out.write(b);
out.write(CRLF, 0, 2);
```

Add a unit test: write a single byte and read the resulting raw bytes.

### A2. Chunk-size parsing throws `NumberFormatException` on malformed input — **MEDIUM**

`Http1MessageParser.java:312`

```java
remainingBytesToProxy = Long.parseLong(consumeAscii(buffer), 16);
```

If a client sends a chunk size like `ffffffffffffffff` (overflows `long`) or non-hex content
that bypasses `isHexDigit` validation by some other path, `Long.parseLong` throws an unchecked
`NumberFormatException`. This is not caught in the parser nor in `Http1Connection.start()`'s
specific catch clauses — it falls through to the generic `catch (Exception e)` and the
connection is closed with a vague "Unhandled error at the socket" log.

**Impact:** Behavior is benign (connection closes) but the log line and stats counter are
wrong. Also it's a DoS amplifier — any oversized chunk size is treated as "internal error"
rather than "bad request".

**Fix:** Wrap in try/catch and throw `HttpException(BAD_REQUEST_400)` (or a `ParseException`
that the connection turns into a 400). Also reject chunk sizes greater than
`maxRequestBodySize` immediately.

### A3. HTTP/1.1 keep-alive idle gap bypasses `requestIdleTimeoutMillis` — **MEDIUM**

`Http1Connection.java:86, 129`

```java
clientSocket.setSoTimeout(requestTimeout);   // line 86 — when a request starts
...
clientSocket.setSoTimeout(0);                // line 129 — after the response finishes
```

After a response completes, the socket timeout is set to **0 (infinite)** before the next
`readNext()` call. A keep-alive client that opens a connection and then never sends another
request remains pinned until the separate `idleTimeoutMillis` watcher (default **20 minutes**)
notices it. The configured `requestIdleTimeoutMillis` (default 2 minutes) does not bound this
gap.

**Impact:** Slowloris-style holding of idle keep-alive connections costs a virtual thread
each. On platform-thread JVMs (JDK ≤17 path) it costs a real thread each. The server's
documented 2-minute "request read timeout" is therefore not enforced between requests.

**Fix:** Either reset `setSoTimeout(requestIdleTimeoutMillis)` before the next `readNext()`
(reading the request line is part of the request), or document that there is no per-request
idle timeout between pipelined requests on a keep-alive connection.

### A4. Empty header values are silently dropped — **LOW**

`Http1MessageParser.java:248-251`

```java
var value = consumeAscii(buffer).trim();
if (!value.isEmpty()) {
    exchange.headers().add(headerName, value);
}
```

RFC 7230 §3.2 allows empty header values (`field-value = *( field-content / obs-fold )` with
field-content being optional). Dropping them silently means user code can never observe a
client-sent empty header.

**Impact:** Low — most clients don't send empty headers. But it's surprising and tests against
spec-compliance tools could flag it.

**Fix:** `exchange.headers().add(headerName, value);` unconditionally (or after a length-bound
check).

### A5. Trailers parsed with naïve regex split — **LOW**

`Http1MessageParser.java:456-468`

```java
private static FieldBlock parseTrailers(String trailerPart) {
    var parsed = new FieldBlock();
    for (String line : trailerPart.split("\\r\\n")) {
        if (line.isEmpty()) continue;
        String[] bits = line.split(":", 2);
        String value = bits.length == 1 ? "" : bits[1].trim();
        if (!value.isEmpty()) {
            parsed.add(bits[0], value);
        }
    }
    RequestTrailers.validate(parsed);
    return parsed;
}
```

Issues:

1. The state machine accepts an LF without a preceding CR (line 373) — the buffer's
   end-marker check `endsWith("\r\n\r\n")` works on the buffer, not on whether the
   accumulated data was strictly CRLF-delimited.
2. Trailer values containing literal `\r\n` (obsolete line folding, deprecated but not
   forbidden) are split into multiple lines.
3. Empty trailer values are silently dropped (same as A4).
4. Trailer name validation uses `bits[0]` directly, with no `tchar` check — invalid bytes
   smuggled in earlier states could end up as header names.

**Fix:** Reuse the state machine for trailers instead of re-parsing as a string. At minimum,
validate names as tchars and preserve empty values.

### A6. Request rejection on `URI_TOO_LONG_414` resets the buffer to `/` — **LOW**

`Http1MessageParser.java:107-114`

```java
var bad = reject != null && reject.status() == HttpStatus.URI_TOO_LONG_414;
if (!bad && buffer.size() < maxUrlLength) {
    append(buffer, b);
} else if (!bad) {
    buffer.reset();
    append(buffer, (byte) '/'); // the request won't last long - give it a temp URL
    request().setRejectRequest(new HttpException(HttpStatus.URI_TOO_LONG_414));
}
```

After rejection, the loop continues to consume target bytes until SP. That's fine, but the
rejected request still goes through the full parse → response cycle, including allocating a
`Mu3Request` with a synthetic `/` URL. If a later handler depends on `request.uri()` (rate
limiters look at headers, some loggers read URI), they'll see `/` instead of the real (oversize)
URI. Probably acceptable since the response is `414`, but worth being aware of.

### A7. Method parsing accepts only uppercase — **LOW**

`Http1MessageParser.java:88-101`

The `METHOD` state only accepts `isUpperCase` bytes. Lowercase methods are rejected. Per RFC
7230 §3.1.1 methods are case-sensitive and registered methods are uppercase — so this is
correct, but it means custom methods using lowercase (for whatever reason) get a parse error
rather than a clean 405.

---

## B. HTTP/2

### B1. Oversized frame triggers stream error without consuming the payload — **HIGH**

`Http2FrameHeader.java:43-49` + `Http2Connection.java:331-405`

```java
// Http2FrameHeader.readFrom
if (length > buffer.capacity()) {
    var errorType = !hasStream || frameType.hasFieldBlock() || frameType == Http2FrameType.UNKNOWN
        ? Http2Level.CONNECTION : Http2Level.STREAM;
    if (errorType == Http2Level.CONNECTION) {
        throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, ...);
    } else {
        throw Http2Exception.stream(Http2ErrorCode.FRAME_SIZE_ERROR, ..., streamId);
    }
}
```

`readFrom` is called from `Http2Connection.start()` at line 337 — *before* the payload is read
(`Mutils.readAtLeast(buffer, clientIn, len)` at line 341 happens after this). When `readFrom`
throws a stream-level FRAME_SIZE_ERROR, the catch at line 397-405 sends `RST_STREAM` and
continues the loop. The payload bytes are still on the wire. The next iteration reads what
it thinks is a frame header but is actually mid-payload.

**Impact:** A peer that sends a single oversized DATA frame to a server that has set
`SETTINGS_MAX_FRAME_SIZE` smaller will desync the connection silently — the server starts
parsing garbage and likely closes with a generic protocol error (or worse, mis-routes data).

**Fix:** Either (a) make this a connection error in all cases (we can't recover anyway, since
the buffer is too small to drain the payload), or (b) drain `length` bytes from `clientIn`
in chunks before continuing. The RFC explicitly allows treating it as a connection error.

### B2. `Http2Stream.state` is not volatile and accessed from multiple threads — **HIGH**

`Http2Stream.java:31` (`private State state;` — no `volatile`), modified at lines 103, 115,
132, 150, 153, 163, 181, 184, 416, 418, 421.

The state is mutated by:

- The HTTP/2 read loop (acceptor's executor thread): `onData`, `onTrailers`, `onReset`,
  `cancel`.
- The request-handler thread (a different executor task): `blockingWrite` flips state to
  `HALF_CLOSED_LOCAL` / `CLOSED` when sending end-stream/RST (lines 415-421).

There is no synchronization. `canReceiveData()` / `canSendFrames()` are read by both threads.
Comments at line 161 (`// todo: thread safety`) and line 393 (`// todo: synchronise access to
the state`) acknowledge this.

**Impact:** Memory visibility — a stream may appear OPEN on one thread after another thread
moved it to CLOSED. Window updates to closed streams, RSTs being sent after the stream is
already closed, write-after-close exceptions in production. Race-y enough that under load
you'd expect intermittent failures rather than determinism.

**Fix:** Make `state` volatile at minimum, or guard transitions with the connection's
`writeQueueLock` (or a per-stream lock). Audit all the places that read state then act on it
for TOCTOU races.

### B3. WINDOW_UPDATE / SETTINGS apply only to outgoing flow — incoming-side stream credit is unbounded if peer sends `INITIAL_WINDOW_SIZE` updates that affect us — **MEDIUM** (verify)

`Http2Connection.java:537-555`

```java
} else {
    var oldSettings = clientSettings;
    var newSettings = settingsDiff.copyIfChanged(clientSettings);
    if (newSettings != oldSettings) {
        clientSettings = newSettings;
        if (newSettings.initialWindowSize != oldSettings.initialWindowSize) {
            for (var stream : streams.values()) {
                try {
                    stream.applyClientSettingsChange(oldSettings, newSettings);
                } catch (Http2Exception e) {
                    throw Http2Exception.connection(e.errorCode(), e.getMessage());
                }
            }
        }
    }
    write(Http2Settings.ACK);
}
```

Per RFC 9113 §6.9.2, `SETTINGS_INITIAL_WINDOW_SIZE` adjusts the *sender's* view of the
*receiver's* window. The peer's setting governs **our outgoing** window (correct here). But
the **server's own** SETTINGS frames (sent during handshake) should similarly resize the
incoming flow control of all open streams when `serverSettings.initialWindowSize` differs
from the default 65535. Currently `Http2Stream` initializes its incoming window from
`serverSettings.initialWindowSize` at construction (line 330) — fine for new streams — but
there is no path that resizes existing streams' **incoming** windows when the server sends a
new SETTINGS frame mid-connection. Today the server never does that, so this is latent.

**Fix:** If you ever expose dynamic SETTINGS update from the server, mirror the per-stream
incoming flow update analogous to `applyClientSettingsChange`.

### B4. ACK queueing happens after the frame is written, but the deadline starts when queued — **LOW**

`Http2Connection.java:142-144, 297-302`

```java
private void queuePendingSettingsAck() {
    settingsAckQueue.add(System.currentTimeMillis() + settingsAckTimeoutMillis);
}
...
// inside drainWritableFramesLocked, after the frame is written:
if (candidate.frame() instanceof Http2Settings) {
    var settings = (Http2Settings) candidate.frame();
    if (!settings.isAck) {
        queuePendingSettingsAck();
    }
}
```

The deadline is computed from `now` at the moment `queuePendingSettingsAck()` runs. If the
write loop is backed up (lots of frames queued), the SETTINGS frame may have been **written
to the socket** much earlier than the deadline is recorded. That biases the deadline in the
peer's favor — but it also means a slow write loop can register a deadline so far in the
future that we don't detect a non-acking peer.

**Impact:** Low. The default settings ack timeout is configurable, peers normally ack
immediately, and this only matters when both the server is heavily loaded and the peer is
misbehaving.

**Fix:** Sample the timestamp earlier, or defer the SETTINGS ack timer until after the
frame is actually flushed.

### B5. `:authority` is parsed but ignored — **MEDIUM**

`Http2Stream.java:240-243, 304-309`

```java
if (n == HeaderNames.PSEUDO_AUTHORITY) {
    if (authority != null) throw new Http2Exception(...);
    authority = line.value();
    iter.remove();
}
...
if (authority == null) {
    // TODO: use this somehow
    authority = host;
} else if (host == null) {
    headers.add(HeaderNames.HOST, authority);
}
```

RFC 9113 §8.3.1: *"If the :authority pseudo-header field is present, the host header field is
ignored. If both fields are present, intermediaries should use :authority and discard host."*
The current code only synthesizes `Host` if no `host` header was present; if both are present
and **disagree**, the server uses `host` (because `:authority` was removed during iteration
and `host` was left in the FieldBlock). Downstream URI building and host-based virtual hosting
will then key off the wrong value.

**Fix:** When both are present, override `host` with `:authority` (or reject if they differ
and you want strict behavior). Also consider validating that `:authority` is present for
non-`CONNECT` requests with no `Host` header.

### B6. Cookie merging only triggers when there are 2+ headers, but no validation of `host` cookies / sizes — **LOW**

`Http2Stream.java:311-315`

```java
var cookies = new ArrayList<String>(2);
cookies.addAll(headers.getAll(HeaderNames.COOKIE));
if (cookies.size() > 1) {
    headers.set(HeaderNames.COOKIE, String.join("; ", cookies));
}
```

Correct in spirit (RFC 9113 §8.2.3 allows merging with `; ` separator) but:

- If a single Cookie header is present with multiple cookies (`Cookie: a=1; b=2`), nothing
  happens — fine.
- The merging adds no separator validation; if a malformed Cookie header sneaks through, the
  combined header is also malformed. Probably fine since downstream parsing handles it.

### B7. Allocation per DATA frame on read path — **PERF / MEDIUM**

`Http2DataFrame.java:53-55`

```java
byte[] data = new byte[dataLength];
buffer.get(data);
```

Every inbound DATA frame allocates a new byte array sized to the frame's data length. On a
multi-megabit upload split into 16 KB frames, that's hundreds of GC-eligible allocations per
second. The data is also handed to `Http2BodyInputStream` which holds it until consumed, so
a slow consumer + fast producer pins memory.

**Fix:** Keep a slice of the connection-level buffer and have `Http2BodyInputStream` consume
it before the next frame is read. Trickier than it looks because the read loop can move on to
the next frame while a body reader is still draining — pooling or copy-on-need is needed.
Easier interim fix: pool the byte[] arrays.

### B8. Unbounded continuation-fragment accumulation — **MEDIUM**

`Http2HeadersFrame.java:88-99, 106-128`

```java
baos = new NiceByteArrayOutputStream(Math.max(32, hpackLength * 2));
...
while (!ended) {
    Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);
    var hf = Http2FrameHeader.readFrom(buffer);
    if (hf.frameType() != Http2FrameType.CONTINUATION) { ... }
    Mutils.readAtLeast(buffer, clientIn, hf.length());
    var cf = Http2ContinuationFrame.readFrom(hf, buffer);
    ...
    baos.write(cf.fragment());
    ended = cf.endHeaders();
}
```

A peer can send an arbitrary number of CONTINUATION frames each up to `maxFrameSize` bytes,
and we accumulate them into a single byte[] before HPACK decoding. `maxHeaderListSize` is
checked **inside** the HPACK decoder — by the time we get there, we may have already
allocated tens of megabytes.

This is the well-known "HTTP/2 CONTINUATION flood" pattern (CVE-2024-27316 class). The
tracker (`HTTP2DO.md`) lists §10.5 "Denial-of-Service Considerations" as `[~]` — partial.

**Fix:** Track a running total of `baos.size()` and enforce a hard cap (e.g., 2× max header
list size). Throw `ENHANCE_YOUR_CALM` or close the connection if exceeded.

### B9. `Http2Connection.activeRequests()` allocates a new `Set` per call — **PERF / LOW**

`Http2Connection.java:622, 775-777`

```java
} else if (activeRequests().size() >= serverSettings.maxConcurrentStreams) {
...
public Set<MuRequest> activeRequests() {
    return streams.values().stream().map(s -> s.request).collect(Collectors.toSet());
}
```

Every new HEADERS frame triggers a stream → an `activeRequests()` call → a stream + collector
+ new HashSet. For the `>= maxConcurrentStreams` check, just use `streams.size()`.

### B10. `discardPayload` over-reads from the socket if called before payload is buffered — **LATENT**

`Http2Connection.java:711-737`

The function relies on the caller having already buffered `len` bytes via `Mutils.readAtLeast`.
The current call site at line 347 does. But the inner `while (len > buffer.capacity())` branch
calls `Mutils.readAtLeast(buffer, clientIn, buffer.capacity())` and then `buffer.clear()` (no
flip), and the outer `Mutils.readAtLeast(buffer, clientIn, len)` reads up to `capacity` bytes
(not just `len`) — followed by `buffer.flip()` which sets `limit=position=0`. Any bytes read
beyond `len` are discarded.

**Impact:** None today (call site pre-buffers). But if a future caller invokes `discardPayload`
without a preceding `readAtLeast`, the next frame's bytes may be silently lost.

**Fix:** Either document the precondition very loudly, or make `discardPayload` self-contained
(only read exactly `len` more bytes if needed).

### B11. Stream-error refund on HEADERS frame error doesn't refund — **LOW** (verify)

`Http2Connection.java:599-654`

When `readLogicalFrame` throws an `HttpException` (e.g., header list too long), we send back
a synthetic 4xx response. Good. But when it throws an `Http2Exception`, the catch at line
397 in `start()` sends RST_STREAM. The HEADERS frame doesn't consume connection-level flow
control credit (HEADERS isn't flow-controlled), so no refund needed there. But the header
fragments may have been partially HPACK-decoded into the dynamic table — that table state
may now be inconsistent with the peer's view. Hard to verify without targeted tests.

**Fix:** Conformance tests around HEADERS that fail mid-decode (e.g., invalid Huffman, oversize
field) — make sure subsequent valid HEADERS still decode correctly. The `RFC7541_*` tests
mostly cover happy paths.

### B12. `readResetStreamFrame` doesn't validate streamId for unknown closed streams — **LOW**

`Http2Connection.java:469-481`

```java
private void readResetStreamFrame(Http2FrameHeader fh) throws Http2Exception {
    var rstStream = Http2ResetStreamFrame.readFrom(fh, buffer);
    int streamId = rstStream.streamId();
    var stream = streams.get(streamId);
    if (stream != null) {
        stream.onReset(rstStream);
    } else {
        if (streamId > lastStreamId || streamId % 2 == 0) {
            throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on rst_stream");
        }
    }
}
```

If the stream is null (already closed), we treat `streamId == 0` as falling into the else
(`0 % 2 == 0` → true → throw). Good. But streamId equal to `lastStreamId` for an idle stream
that was created and then closed quickly — same handling. Fine.

There is no sanity check on the error code in the RST itself (could be a non-spec value).
RFC says unknown error codes are treated as INTERNAL_ERROR and the receiver must not raise
a connection error. Current behavior just logs.

### B13. `Http2Connection` write-loop logs `INFO` per frame — **PERF / LOW**

`Http2Connection.java:283, 295, 342, 346, 473, 499, 534, ...`

```java
log.info("frame=" + frame.getClass().getSimpleName() + " required=" + ... + " available=" + ...);
log.info("Writing " + candidate.frame());
log.info("read fh = " + fh);
```

At hundreds of frames per second per connection, this is one log line per frame on each side.
Even with logging filtered out at the appender, the string concatenation runs.

**Fix:** Drop to TRACE or guard with `log.isInfoEnabled()`. Same applies to
`Http2OutgoingFlowController.withdrawIfCan` at line 107-110 (`log.info("hmmm")` is also
clearly debugging leftover).

### B14. `Http2OutgoingFlowController.withdrawIfCan` has dead-debug branch — **MINOR / CLEANUP**

`Http2OutgoingFlowController.java:108-110, Http2IncomingFlowController.java:90-92`

```java
if (bytes == 65535) {
    log.info("hmmm");
}
```

Looks like a debugging probe left behind in both controllers. Should be removed.

### B15. `Http2HeadersFrame.writeTo` uses confusing 32-bit AND mask on a byte — **CLEANUP**

`Http2HeadersFrame.java:181`

```java
toWrite[4] = (byte) (toWrite[4] & 0b11111111111111111111111111111011);
```

The intent is to clear bit 2 (END_HEADERS = 0x04). Java promotes `byte` to `int` for the AND;
the upper 24 bits are irrelevant. This works but the readable form is `toWrite[4] & ~0x04`
(or `& 0xFB`).

### B16. `Http2DataFrame.readFrom` padding bound is correct but documentation unclear — **VERIFY**

`Http2DataFrame.java:40-51`

```java
if (header.length() < 1) {
    throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "DATA frame missing pad length");
}
padLength = buffer.get() & 0xFF;
if (padLength >= header.length()) {
    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Padding length too large");
}
dataLength = header.length() - 1 - padLength;
```

The check `padLength >= header.length()` is correct given `header.length() = 1 (pad-length
byte) + dataLength + padLength`, but the throw uses `Http2Exception(...)` with no level —
which defaults to stream level. RFC 9113 §6.1: padding error MUST be a connection error.

**Fix:** Use `Http2Exception.connection(...)`.

### B17. Per-frame logging in flow controllers leaks credit values — **PRIVACY / LOW**

`Http2IncomingFlowController.java:63, 89` — logs stream credit at INFO. Not strictly a privacy
issue but combined with header logging it's noise that ties to client behavior.

### B18. `Http2Connection.startRequest` doesn't catch `RejectedExecutionException` — **MEDIUM**

`Http2Connection.java:694-708`

```java
executorService.submit(() -> { ... });
```

If the executor is bounded and rejects (e.g., user-supplied `withHandlerExecutor(...)` that
isn't a virtual-thread per-task executor), `RejectedExecutionException` propagates up through
`readHeaders()` → `start()`'s read loop. The catch at line 397 only handles `Http2Exception`,
so a RuntimeException kills the read loop and the connection.

**Impact:** Real for users who pass a bounded thread pool (the API allows it). The HTTP/1
acceptor handles overload explicitly with a 503 (`ConnectionAcceptor.handleOverload`). HTTP/2
doesn't have an equivalent.

**Fix:** Wrap `submit` in try/catch; on rejection, send `RST_STREAM(REFUSED_STREAM)` for the
new stream and continue, mirroring the H2 behavior of the `maxConcurrentStreams` branch above.

### B19. `peerGoAwayLastStreamId` not used to gate new outbound work — **VERIFY / LOW**

`Http2Connection.java:55, 502-509`

The peer's GOAWAY tells us streams **above** `peerGoAwayLastStreamId` won't be processed by
the peer. As a server we don't initiate streams, so this matters less, but we **could** still
be writing responses to streams the peer has signaled it won't accept further frames on
gracefully — RFC requires we still finish them. Currently `peerGoAwayLastStreamId` is set
but never read again. Probably fine.

---

## C. Cross-cutting

### C1. `BaseHttpConnection.handleExchange` swallows handler-thrown exceptions only at the connection level — **VERIFY**

`BaseHttpConnection.java:67+` (and follow-on cleanup paths). When a handler throws after
having written headers but before completing the body, both protocols try to "complete" the
response. For HTTP/1 that means the connection is closed (good). For HTTP/2 it means
RST_STREAM with INTERNAL_ERROR (good). Worth a targeted test that the response state machine
correctly detects "already wrote 200 OK + 100 bytes, then threw" and RSTs rather than tries
to write trailers.

### C2. `Http1MessageParser.append` throws `IllegalStateException` on overflow — **LOW**

`Http1MessageParser.java:507-510`

```java
private void append(ByteArrayOutputStream baos, byte b) {
    baos.write(b);
    if (baos.size() > maxBufferSize) throw new IllegalStateException("Buffer is " + baos.size() + " bytes");
}
```

`IllegalStateException` is unchecked and not specifically handled. It bubbles up to
`Http1Connection.start()`'s generic `catch (Exception e) { log.error(...) }`. Should be a
`HttpException(REQUEST_HEADER_FIELDS_TOO_LARGE_431)` or `URI_TOO_LONG_414` so the client
receives a proper status. The header-overflow path already produces 431 via
`onHeaderChar()`; the URI-overflow path already produces 414 via the dedicated branch. This
exception fires only when something bypasses both — i.e., a malformed input mid-state. Worth
an audit.

### C3. `FieldBlock` lookups are O(n) — **PERF / MEDIUM**

`FieldBlock.java:23-31, 51-58, 175-211`

`get`, `getAll`, `contains`, `containsValue` all walk the entire list on every call. Headers
are read multiple times per request (CORS, JAX-RS, content negotiation, logging,
`closeConnectionRequested`, etc.). For requests with 20+ headers this adds up.

**Fix:** Build a lazy `Map<HeaderString, List<FieldLine>>` on first lookup and invalidate on
mutation. Preserve insertion order separately for iteration.

### C4. Date header is formatted per response — **PERF / MEDIUM**

`Http1Response.java:30-32`, `Http2Response.java:46-49`

```java
if (!headers().contains(HeaderNames.DATE)) {
    headers().set("date", Mutils.toHttpDate(new Date()));
}
```

`new Date()` + `Mutils.toHttpDate` (presumably `SimpleDateFormat`-based) per response. Standard
servers cache the formatted second-precision date in a volatile string and refresh once per
second.

### C5. HPACK dynamic table size update from peer doesn't validate against our `SETTINGS_HEADER_TABLE_SIZE` — **VERIFY**

`Http2Connection.java:323` updates the encoder's table size when the peer's settings change.
The decoder's `allowedMaxTableSize` is set at creation (line 326) based on
`serverSettings.headerTableSize`. RFC 7541 §6.3 says the encoder may signal a smaller table
size dynamically — we accept that via `FieldBlockDecoder.changeTableSize(...)`. Confirm the
upper bound is enforced (peer can't grow our decoder table beyond what we advertised).

---

## D. Recommendations

Before fixing any of the above, write a regression test first — the project's RFC-conformance
test pattern (`RFC7541_*`, `RFC9113_*`) is well-suited to this. Suggested priority order:

1. **A1** (chunked write-byte) — clearly broken, likely under-tested, easy fix.
2. **B2** (Http2Stream.state visibility) — racy, hard to reproduce, real production risk.
3. **B1** (oversized frame stream-error not consuming payload) — easy DoS / desync.
4. **B8** (CONTINUATION flood) — known DoS class, partly tracked in `HTTP2DO.md`.
5. **B5** (`:authority` ignored) — spec compliance, observable behavior.
6. **A3** (HTTP/1 keep-alive idle gap bypasses request timeout) — operational risk.
7. **B18** (`RejectedExecutionException` kills read loop) — real for users with bounded
   executors.
8. **B7 / B13 / C3 / C4** — performance, batchable as one cleanup pass.

Each item links to the specific lines so reviewers can verify quickly. Where I marked
**VERIFY**, treat the entry as "this looks suspicious; please confirm with a test before
acting on it" — I did not run a debugger or write tests for these.
