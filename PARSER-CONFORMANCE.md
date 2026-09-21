# Deterministic parser conformance coverage

These are ordinary JUnit tests with fixed inputs and expected outcomes. No fuzzing,
random input generation, or saved fuzz corpus replay is involved. This inventory
records specific coverage, not a claim of complete RFC compliance.

| Tests | Requirements and boundaries |
| --- | --- |
| `HpackConformanceTest` | [RFC 7541 sections 2–6](https://www.rfc-editor.org/rfc/rfc7541.html): all integer prefix widths at their continuation boundary; unavailable indexes; literal indexing modes and never-indexed metadata; duplicate field ordering; table reset/restore, exact capacity, eviction and oversized entries; indexed names whose source is evicted; empty values/blocks; buffer position/limit, direct/read-only/sliced buffers. Also checks continued compression-context updates after a header-list limit rejection ([RFC 9113 section 4.3](https://www.rfc-editor.org/rfc/rfc9113.html#section-4.3)). |
| `HuffmanConformanceTest` | [RFC 7541 section 5.2 and Appendices B/C](https://www.rfc-editor.org/rfc/rfc7541.html#section-5.2): every allowed padding length from zero through seven bits, independent fixed codewords and published examples, required rejection of invalid padding/EOS, declared-length bounds, and preservation of bytes following a string. |
| `Http1FramingConformanceTest` | [RFC 9112 sections 5–7](https://www.rfc-editor.org/rfc/rfc9112.html): fixed/zero-length bodies followed by another request; chunk extensions, mixed-case hexadecimal sizes, trailers kept separately and consumed once; case-insensitive names and optional whitespace. Fixed read sizes include one-byte reads and a whole message in one read. |
| `Http2FrameHeaderConformanceTest` | [RFC 9113 sections 4.1 and 6](https://www.rfc-editor.org/rfc/rfc9113.html#section-4.1): stream-ID requirements, fixed frame lengths, reserved-bit handling, unknown frame types, and unsigned length decoding across byte boundaries. Connection-state and payload validation are outside this class. |
| `WebsocketUpgradeHandoffTest` | Normal upgrade followed by ordered text messages, ping/pong and normal closure. The client waits for 101 before sending frames. Handshake writes vary in size; TCP is allowed to coalesce them. |

Existing `FieldBlockDecoderTest` additionally checks the RFC 7541 Appendix C.3/C.4
request sequences, integer overflow/truncation, and configured header/URI limits.
The existing encoder, table, HTTP/1 body-stream and frame-header tests remain part
of focused verification.

## Regression found by these tests

A valid chunked HTTP/1 request with trailers left the trailer section's final LF
unconsumed. Reading the next request on the same connection consequently failed
at `REQUEST_START`. Advancing the parser position before returning the body-end
marker fixes this; the regression covers five deterministic read sizes.

## Scope still requiring separate coverage

This addition is not a full review of HTTP/1 field grammar, response framing,
all RFC 7541 response examples, SETTINGS acknowledgment transitions, HTTP/2
continuation/stream state, TLS, or resource/backpressure behavior. The release
checklist in the companion conformance repository remains authoritative for
broader release work. No retained fuzz findings are classified by these tests.

## Repeatable focused check

Run with the desired supported JDK (11, 17, 21, or 25):

```sh
mvn -o -Dtest=HpackConformanceTest,HuffmanConformanceTest,Http1FramingConformanceTest,Http2FrameHeaderConformanceTest,Http1MessageParserTest,Http1BodyStreamTest,FieldBlockDecoderTest,FieldBlockEncoderTest,HpackTableTest,HuffmanDecoderTest,HuffmanEncoderTest,Http2FrameHeaderTest test
```

The full JDK 21 CI command is `mvn -Pnullaway verify`; other JDKs use `mvn verify`.

## Validation on September 21, 2026

- 109 new cases in the four conformance classes; 160 focused tests including
  existing related suites passed on each of JDK 11, 17, 21 and 25.
- The full JDK 21 `mvn -o -q -Pnullaway verify` attempt ran 2,545 tests:
  2,539 passed, five were skipped, and `MuServerTest.returns400IfNoHostHeaderPresent`
  timed out. A targeted rerun of that method with the same profile and `verify`
  passed. This is not recorded as a clean full-suite pass.
- Raw full-run XML reports and per-JDK new-test reports are preserved locally
  under ignored `target/parser-conformance-20260921/`; Maven logs are under
  `/tmp/mu4-parser-*`. No fuzzing was run.
