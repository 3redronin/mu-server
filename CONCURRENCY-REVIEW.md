# Disposition of the concurrency review

Follow-up to [issue #198](https://github.com/3redronin/mu-server/issues/198), based on
[PR #195 and its overview](https://github.com/3redronin/mu-server/pull/195#issuecomment-5098246923).
The original stack is preserved. Follow-ups are stacked as
[#212](https://github.com/3redronin/mu-server/pull/212),
[#213](https://github.com/3redronin/mu-server/pull/213),
[#214](https://github.com/3redronin/mu-server/pull/214), and this documentation/cleanup change.
The current contract is [HTTP2-CONCURRENCY.md](HTTP2-CONCURRENCY.md).

| Note or question | Disposition |
| --- | --- |
| Six configurable executors; #144–149, #158 | Keep one application executor; Mu owns independent I/O workers and a timer. Remove executor-identity switches and fallback pools. Retain a small nested-task FIFO. |
| Capacity and the historical 400 | Add a server-wide unfinished-request limit, default 1000, zero unlimited. Include queued and suspended work and SSE; release before detached callbacks and after WebSocket upgrade. Keep the separate HTTP/2 stream limit. |
| Parked writer versus rescheduling | Retain the existing writer rescheduling, including the regression that one writer can serve multiple connections. Its modest implementation also releases idle platform threads. |
| 135-01/02: reset lifetime and retainResetState | Preserve tombstones/fences and use named retention and unread-credit policies. A late DATA frame cannot replace a terminal body error. Refund its connection credit only. |
| 135-03: QueueWrite versus WritableFrame | Keep producer commands separate from selected, credit-reserved fragments. These are different ownership stages. |
| 135-04: commandBatch | Keep the owner-confined reusable batch; each batch bounds producer work before the writer gets another opportunity to send. |
| 135-05: termination causes | Preserve distinctions between diagnostic cause, wire error and public response outcome. Do not add more public terminal states. |
| 135-06 and #144: constructors | Retain package-level component constructors; executor tests use a resource factory and forced wire races use a typed connection fixture. |
| 135-07/09: allocation and collections | Keep owner-confined mutable collections; allocate local completion-listener queues lazily. Measure before attempting broad command/buffer allocation changes. |
| 135-11/12: zero-length bodies | Preserve immediate application EOF separately from peer END_STREAM and protocol retirement. |
| 135-14: WINDOW_UPDATE bypass | Keep the bypass of flow-blocked same-stream DATA; it advances the opposite direction and avoids circular waiting. |
| 135-16/17, #140/#161: rejected streams and shutdown race | Keep lightweight protocol-only rejected entries. Existing #161 conversion/enqueue transaction closes the identity gap. Add the forced final-GOAWAY/rejection interleaving. Do not allocate fake handlers or another rejection executor. |
| #140: overload notification | Preserve the later stack fix that queues 503 before notifying. Notifications use the application executor and may be dropped on visible rejection. |
| #148: read rejection and recursive callbacks | Separate internal reads from callback delivery, preserve body ownership and the FIFO, attempt onError through application dispatch, and fail/clean up when required dispatch is unavailable. |
| #150: accepted task removed by caller | Document executor lifecycle and visible-rejection requirements. Track Mu-owned registration cleanup; retain bounded stop behavior. Arbitrary silent removal from a caller queue cannot be detected. |
| #151: WebSocket exceptions during shutdown | Distinguish application callback failure from shutdown-induced transport failure, and still deliver onError for application failure. |
| #153/#170: SETTINGS ACK timeout | Keep configurability, the 10-second default, and immediate expiry for zero. Register before output; arm after output; fatal scheduling failures are not suppressed if ACK wins. |
| #155: completion resubmission | Preserve nested application turns. Rejected continuations abort their accepted exchange; they do not escape onto an unbounded pool or reader. |
| #156: Phaser limit | Replace Phaser with idempotent registrations and a counter/condition; regression covers 70,000 pending callbacks and repeated drains. |
| #157: queued JAX-RS resume after DONE | Claim PROCESSING under the state lock before invoking serialization. Completion that wins first prevents queued serialization. |
| #159: SETTINGS delta and HPACK sizes | Keep existing-stream window deltas and the encoder's minimum/final table-size tracking. They express distinct protocol requirements. |
| #162: reset/body credit race | Record terminal failure independently of queued frames; refund late DATA/padding once to the connection, preserving the first failure. |
| #164: large connection class/GOAWAY | Group shutdown/admission state and decisions under the existing connection lock; retain atomic admission/publication. Document SETTINGS acknowledgement state. |
| #166: queued accepted socket | Assert client EOF or TCP reset after shutdown; the queued task cannot start a handler later. |
| #168/#171: publication and overflow arithmetic | Retain credit publication before WINDOW_UPDATE write, and END_STREAM publication after writeTo and before flush. Name the total-credit overflow check. A raw write may expose bytes before it returns; this is not an exact peer-observation boundary. |
| #169/#179: outstanding/replayed pongs | Replace timestamp authentication with at most 64 outstanding random IDs and monotonic send times. Consume each once, evict oldest, ignore unmatched samples while still delivering pong events. |
| #182: rate limits | Keep request-aware rate policies in application work and count 429 as overload, separately from malformed requests. A richer 429 policy/metric can follow separately. |
| #185–189: header bounds and HPACK | Keep accumulation bounds, integer validation, field-section and sensitivity fixes. Map HPACK descriptive variables explicitly to RFC I/M. Do not claim comprehensive coverage of every HTTP/2 denial-of-service pattern. |
| #191: nullable async body | Keep the already-fixed claim-before-dispatch behavior and final non-null body stream. |
| Async API deprecation | Keep handleAsync supported. Move implementation-only dispatch methods to a hidden core/JAX-RS bridge with custom-handle compatibility. |
| Multiple async writes, completion/error races | Preserve ordered submissions and callbacks; document ownership and backpressure. Successful completion drains accepted output. Error completion/cancellation terminates active I/O before releasing buffers and fails queued work. |
| Two-hour blocking wait | Remove it after making interrupt/future cancellation authoritative. A queued frame cannot continue using a returned buffer. |
| Detached completion listeners | Keep ordered delivery for listeners registered before completion and bounded stop tracking. Late registrations have no ordering guarantee. Document overlap with later requests and lack of a same-thread guarantee. |
| synchronized and virtual threads | Move future completion outside the request monitor; annotate actual lock ownership. Avoid a blanket monitor rewrite. Blocking I/O and unknown user code remain outside state locks. |
| One WebSocket lock | Keep output serialization separate from lifecycle/callback state so blocked socket writes cannot prevent abort/timeout. Remove the shared-executor mode instead. |
| Catching Error | Isolate ordinary listener failures, including nonfatal AssertionError. Rethrow VirtualMachineError and ThreadDeath; do not silently convert them to HTTP errors. |
| idleTimeMillis and publishLatest | Preserve public milliseconds, use monotonic internal elapsed time, and rename publication to advanceIfLater. Keep ConnectionAcceptedTime's specific meaning. |
| List.copyOf, comments and reflection | Keep immutable snapshots where semantically appropriate; document non-obvious lifecycle fields. Replace private-field reflection in concurrency tests with typed fixtures. |
| #154, #160, #163, #172–178, #180–181, #184, #190–192, #194–195 | Retain the accepted protocol ordering, timing, isolation, body ownership and terminal-state fixes, subject to the specific follow-ups above. |

Explicitly deferred: continuous disconnect monitoring for suspended HTTP/1 requests
(the existing disabled regression remains), more public terminal states,
reset-after-response as a new wire policy, a generic timestamp type, and speculative
allocation optimization. A competing HTTP/1 socket reader would require a separate
input-ownership, pipelining, half-close and TLS design.

## Validation

The code validated below, before PR review fixes, is commit `970aef7997bb7531ca8826df179c628e66bd605b`.
The temporary integration combines it with current mu4
`4526b6543794335c0a3b160a0bbf09789eb74233`; it merges without conflicts.
Its tree is `60b841a0f031fe3b2952f7129b0cfe2b391792a7`.
Neither original branch is modified by that integration check.

| Build | Result |
| --- | --- |
| Temurin 11, 17, 21, 25 | 1,744 tests each; zero failures/errors; five existing skips |
| Java 21 Error Prone/NullAway | Passed |
| Javadoc and dependency analysis | Passed on all four runtimes |
| Temporary current-mu4 integration, Java 21/NullAway | 1,767 tests; zero failures/errors; five existing skips |

Clean `mvn verify` was used for Java 11, 17 and 25. Java 21 ran the full
`-Pnullaway verify` suite; a documentation-only warning was then corrected and
packaging/static checks rerun without repeating unchanged tests. Earlier follow-up
commits also have the required interruption/nullability fixes folded into them,
so they do not depend on the last PR merely to compile.

Independent clients verified HTTP/1 keep-alive, concurrent HTTP/2 responses,
small credit updates with slow reads, suspended SSE and WebSocket echo on Java 11
and 21, against both #195 and the final implementation. Three alternating runs
per implementation/runtime ran without other local builds. All protocol checks
passed. Raw observations and workload details are in
[validation/concurrency-followup.json](validation/concurrency-followup.json).

Median elapsed times (seconds; each cell is original → final):

| Workload | Java 11 | Java 21 |
| --- | --- | --- |
| 1600 HTTP/1 requests, 16 connections | 0.295 → 0.302 | 0.312 → 0.321 |
| 64 HTTP/2 streams, 16 KiB each | 0.081 → 0.097 | 0.058 → 0.064 |
| Same HTTP/2 workload, small windows/slow reads | 0.543 → 0.489 | 0.459 → 0.521 |
| 64 suspended SSE responses | 0.229 → 0.225 | 0.216 → 0.218 |
| 200 WebSocket echoes over 20 sessions | 0.109 → 0.114 | 0.096 → 0.108 |

These short local runs show both increases and decreases; the Java 21 slow-read
case was about 62 ms slower. They establish continued protocol progress under
the tested workloads, not a general performance improvement or a production
capacity claim. No broad command-allocation optimization was made on this evidence.
