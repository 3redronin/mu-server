# Constrained transport profiling and bounded TLS output

This investigation compares the preserved mu3 implementation, mu4-blocking and
mu4-nio with identical bounded response fixtures. It separates diagnostic
profiles from unprofiled comparisons and checks small-response contention before
accepting bulk-throughput improvements. The selected production change combines
ready TLS records in a bounded output buffer to reduce socket write calls. An HTTP/2 grouping experiment
was discarded because it delayed foreground responses on the same connection.

In the final 1 MiB HTTPS comparison, selected NIO throughput increased 39.5%
on Java 21 and 42.1% on Java 25, with about 19–20% less CPU per response and
21–22% lower p99. It reached throughput comparable to blocking in that workload.
Plain HTTP/1, small-response throughput and HTTP/2 CPU efficiency still have
substantial gaps, detailed below.

The [portable results](deep-profile-20261023.json) contain phase settings,
source/class identities, medians, observed ranges, profile summaries and the
validation audit. The full local report, raw ledgers, frozen fixtures and JFRs
are in the sibling directory
`nonblocking-validation/deep-profile-20261023/report.html`.

## Diagnosis

The first profiles used one connection, one stream and one NIO transport owner
to expose serial work. A second set used eight connections and four owners.
Each implementation ran separately with ten seconds of warm-up and fifteen
seconds of measurement on Java 21. CPU sampling used async-profiler 4.6's
`ctimer` event at 1 ms; wall, sampled allocation and lock events were recorded
separately. These profiled runs are diagnostic, not speed-comparison results.

For a 1 MiB HTTPS response, the original NIO implementation made about 70
process write-family calls, blocking about 66, and mu3 about two in the
single-connection profile. Native socket output appeared in about 66% of NIO
CPU samples. These counters include selector/control writes; the native stack
is the socket wrapper, not a detailed kernel profile. The evidence points to
frequent ciphertext output calls as a substantial avoidable cost.

The four NIO owners also impose a different parallelism limit from eight
blocking connections executing their writes on workers. This is why CPU per
response accompanies throughput: adding owner threads can raise throughput
while consuming more CPU. Earlier [owner-count controls](README.md#october-20-eight-isolated-optimization-experiments)
found that tradeoff and an HTTP/2 regression, so this investigation keeps the
four-owner default and reduces work within it.

HTTP/2 has an additional scheduling cost. Its asynchronous writer encodes a
DATA fragment, borrows it to the transport, and resumes on a worker after that
fragment drains. The next fragment of the same pending write cannot be selected
until the first is acknowledged. A larger ready-frame byte threshold alone
cannot combine these fragments. The eight-connection NIO profile included
roughly 19% of CPU samples in worker task retrieval, 16% in parking and 13% in
unparking. These inclusive categories overlap. Lock contention was small
relative to CPU time; sleeping wall-clock samples are not counted as CPU cost.

This explains why tuning only socket sizes cannot close every gap. Reducing
HTTP/2 worker/owner transfers requires preserving frame-level credit,
cancellation and scheduling opportunities. Arbitrary encoders and application
callbacks must still stay off the transport owner.

## Selected TLS change

The implementation is in [TlsEngineDriver](../../src/main/java/io/muserver/TlsEngineDriver.java),
[TlsOutputBufferPool](../../src/main/java/io/muserver/TlsOutputBufferPool.java) and
[ChannelConnection](../../src/main/java/io/muserver/ChannelConnection.java).

Each transport owner lazily allocates one 130 KiB heap buffer shared by its
connections. A ready application write can combine at most eight engine wraps
and 128 KiB of plaintext in that buffer. The connection's existing 131,216-byte
maximum plaintext service allowance remains unchanged. After acknowledging a
large TLS batch, the connection yields rather than wrapping the small unused
remainder of that allowance into an extra record. Batching introduces no timer,
flush delay or larger application queue.

The buffer remains leased until its ciphertext drains. A peer that stops
reading can retain only the one shared lease on its owner; other connections
fall back to their existing per-connection record buffers. Once the ciphertext
drains, the shared buffer can serve another connection even while plaintext
acknowledgement is being delivered in smaller pieces.

The bound costs 520 KiB of additional fixed heap storage with four owners once
all four have used it. Java's heap-to-socket staging also grows on Java 21;
whole-process memory must include that native storage. In the final Java 21
TLS comparison, JMX buffer-pool readings grew from 0.125 to 0.563 MiB, about
449 KiB across four owners. Heap batching plus that observed native increase
total about 0.95 MiB of fixed storage in this configuration. This is an owner-level
cost, not a larger idle buffer on every TLS connection. The implementation
still performs the same record encryption and retains the ordinary buffer
bounds and provider overflow path.

New tests exercise TLS 1.2/1.3, direct/read-only sources with nonzero offsets,
zero/partial/failing socket writes, changing acknowledgement budgets, shared
buffer reuse, provider overflow/stall/failure, delegated tasks, TLS 1.2
renegotiation and TLS 1.3 key updates with pending ciphertext, failure-alert
ordering, and lease return on abort. The transport returns any outstanding
lease when retirement follows owner failure.

An intermediate eight-record version spent the small remaining byte allowance
on an extra short TLS record. Separate profiles detected a 3–5% allocation
increase per response. Yielding after a large acknowledged batch removed that
work: the paired pilot gained another 5% throughput and cut sampled allocation
by about 7% relative to that intermediate version, while improving foreground
fairness. Historical `final-*` result directories contain the intermediate
version; `release-*` directories contain the selected implementation.

## Rejected HTTP/2 change

An experiment grouped two ordinary DATA frames into one transport borrow when
only one stream was pending. Saturated throughput improved by about 3% and CPU
per response fell about 6–7% in the short pilot. However, foreground p99 on a
connection sharing one bulk stream and paced small requests increased about
14% on Java 21 and 28% on Java 25. A second client implementation reproduced
the regression direction. The grouping and its additional encoding/cache code
were removed.

The reusable [HTTP/2 fairness probe](h2_writer_fairness.py) preserves this
counterexample. It keeps one 1 MiB stream active and sends 128-byte requests at
500/s on the same TLS connection. It validates every completed body, requires
bulk completions in every measurement second, records client dispatch lateness
and checks normal JVM shutdown. It is a Python contention/correctness probe,
not a maximum-throughput generator. An injected failure with both stream types
active verified cleanup.

The existing [busy-writer probe](busy_writer_fairness.py) now also supports
`--protocol https1`. It keeps eight native bulk clients active alongside one
paced foreground connection on a shared owner. This tests a different fairness
boundary from HTTP/2 multiplexing or stopped readers.

For example, from the repository root:

```sh
../mu-conformance/.venv/bin/python benchmarks/nio-baseline/busy_writer_fairness.py \
  --before ../nonblocking-validation/deep-profile-20261023/tls-final/after-async \
  --after ../nonblocking-validation/deep-profile-20261023/release-selected/after-async \
  --out ../nonblocking-validation/tls-writer-recheck \
  --protocol https1 --jdks 21,25 --repeats 2 --warmup 3 --duration 8

../mu-conformance/.venv/bin/python benchmarks/nio-baseline/h2_writer_fairness.py \
  --before ../nonblocking-validation/deep-profile-20261023/tls-final/after-async \
  --after ../nonblocking-validation/deep-profile-20261023/h2-pairs/after-async \
  --out ../nonblocking-validation/h2-writer-recheck \
  --jdks 21,25 --repeats 2
```

Use a new output directory. The second command intentionally compares the
rejected frozen candidate, not the selected production change. Both probes
verify frozen inputs and preserve the controller source used for each run.

## Remaining work identified by the source review

The TLS change targets output call overhead. It does not remove HTTP/2's
per-fragment worker scheduling or the small-response request/response handoffs.
Those controls are reported alongside the improved HTTPS bulk case.

In `Http2WriteCoordinator`, `PendingWrite.fragmentInFlight` deliberately stops
a second fragment from borrowing the same source before the first completes.
`Http2Connection.AsyncWriteDriver` then waits on transport progress before
completing that fragment, processing queued commands and selecting another.
This is also where resets, window changes, END_STREAM publication and fairness
between streams take effect. Merely increasing the batch threshold leaves that
handoff intact; allowing more fragments in flight changes those guarantees.

The preserved mu3 implementation also dispatches handlers through an executor
(`NettyHandlerAdapter.onHeaders`). Its advantage is not evidence that running
application handlers on I/O owners is necessary. The promising target is the
extra internal protocol/output scheduling visible in the profiles.

A useful next prototype would separate internal frame progress from application
completion and encoding, then measure whether fewer worker dispatches can
preserve each frame's scheduling opportunity. It would need the same-connection
fairness probe, stream/connection window exhaustion, reset during partial output,
SETTINGS changes and source reuse after acknowledgement as acceptance checks.
Moving the existing writer task wholesale onto the I/O owner is unsuitable:
frame encoding and completion can invoke application work. The discarded
pairing experiment shows why bulk throughput alone is insufficient evidence.

## Comparison method

Bulk responses submit one bounded async 1 MiB heap-buffer write and then call
`complete()`, which orders completion after that write. Each response has only
one body write outstanding. The 128-byte fixture writes synchronously. Paused-reader controls
use one bounded 8 MiB write per stopped peer. The payload arrays are immutable
and shared; unlimited application writes are outside this investigation.

The native load generator is frozen nghttp2/h2load 1.70.0. Native trials check
body hashes before and after load and reconcile full successful-response logs;
they do not hash every body during load. Fairness clients hash every completed
foreground body, and the HTTP/2 client hashes completed bulk bodies as well.

Server CPUs are 0–3,8–11 and generator CPUs 4–7,12–15. Each JVM has a 512 MiB
maximum heap, a 1 GiB cgroup limit and no swap. NIO normally uses four owners;
fairness uses one shared owner. Both mu4 implementations use PLATFORM workers
on Java 21 and VIRTUAL workers on Java 25. Mu3 retains its Netty behavior.
HTTPS and HTTP/2 use TLS 1.3 with `TLS_AES_128_GCM_SHA256`, without compression.
The measured TLS path uses JDK engines, including Netty's `JdkSslEngine` in the
mu3 profiles.
Sources, fixture classes, production classes and dependencies are frozen and
verified before and after the phases.

| Implementation | Frozen source |
| --- | --- |
| mu3 | `78f025511e4edbaf5681ece0159967fc40d12ecb` |
| mu4-blocking | `50ad1a7072fffe959770531204a09b2ddd3b4637` |
| Original mu4-nio | Production digest `338f15eafccd642392b832d2c11941bc3a2eefae35549f96efe27e90e8a4034a` (unchanged between `e78fe2e` and `17a5890`) |
| Selected mu4-nio | Production digest `8785d21fb584c43b2eda111b38ba8fe89151ec0da840fe8c37e3a6dbb9487704` |

Final bulk comparisons have ten seconds of warm-up and fifteen measured
seconds, with two order-balanced repeats on each JDK. Additional phases compare
HTTPS at a fixed total rate, small responses, stopped readers and an HTTP/2
1,023-byte receive window. CPU profiles are collected in a separate final
phase. All builds, regression tests and load trials run serially.

These are loopback measurements on one laptop during an overnight run. Two
repeats show an observed range, not statistical significance. The container
configuration selects Serial GC and an ergonomically sized starting heap;
results should not be generalized across GC, network and workload choices.
Cgroup memory and RSS include native storage. JMX buffer pools omit some native
memory, particularly on Java 25; they are supporting observations rather than
a complete comparison. Short memory peaks are sensitive to GC and allocation
rate. Context-switch deltas cover threads surviving both boundaries.

<!-- FINAL_RESULTS -->

## Final observations

Medians of two reversed-order runs; see the JSON and local HTML for ranges,
tail latency, CPU, memory and raw evidence. Bulk rates are 1 MiB responses/s.

| Java | Protocol | mu3 | mu4-blocking | Original NIO | Selected NIO |
| --- | --- | ---: | ---: | ---: | ---: |
| 21 | HTTP/1 | 18,181 | 17,748 | 12,870 | 13,049 |
| 21 | HTTPS/1 | 7,792 | 7,901 | 5,594 | 7,805 |
| 21 | HTTP/2 TLS | 3,266 | 2,002 | 2,742 | 2,738 |
| 25 | HTTP/1 | 18,922 | 18,417 | 13,206 | 13,156 |
| 25 | HTTPS/1 | 7,338 | 8,091 | 5,775 | 8,205 |
| 25 | HTTP/2 TLS | 2,684 | 2,623 | 2,818 | 2,812 |

For HTTPS, the original-to-selected NIO changes are:

| Java | Throughput | p99 | CPU/response | Write-family calls/response |
| --- | ---: | ---: | ---: | ---: |
| 21 | +39.5% | -21.9% | -18.9% | -82.0% |
| 25 | +42.1% | -21.4% | -19.6% | -81.9% |

The separate 128-byte response control uses five seconds of warm-up and
eight measured seconds. Its rates are not comparable to the 1 MiB table:

| Java | Protocol | mu3 responses/s | Blocking responses/s | Original NIO responses/s | Selected NIO responses/s |
| --- | --- | ---: | ---: | ---: | ---: |
| 21 | HTTP/1 | 140,955 | 159,123 | 106,298 | 106,463 |
| 21 | HTTPS/1 | 131,928 | 139,979 | 98,509 | 97,575 |
| 25 | HTTP/1 | 143,355 | 176,078 | 141,332 | 140,300 |
| 25 | HTTPS/1 | 119,261 | 145,543 | 118,729 | 119,169 |

Intermediate HTTPS payload controls compare original and selected NIO with
eight connections and four owners, five seconds warm-up and eight measured seconds:

| Java | Payload KiB | Original responses/s | Selected responses/s | Throughput change | Original p99 ms | Selected p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 21 | 16 | 52,369 | 52,272 | -0.2% | 0.520 | 0.521 |
| 21 | 64 | 35,243 | 39,935 | +13.3% | 0.633 | 0.631 |
| 21 | 128 | 25,788 | 31,623 | +22.6% | 0.725 | 0.658 |
| 21 | 256 | 16,198 | 21,779 | +34.5% | 0.999 | 0.837 |
| 25 | 16 | 67,791 | 67,488 | -0.4% | 0.462 | 0.472 |
| 25 | 64 | 40,812 | 46,544 | +14.0% | 0.571 | 0.597 |
| 25 | 128 | 27,970 | 35,126 | +25.6% | 0.668 | 0.603 |
| 25 | 256 | 17,332 | 22,868 | +31.9% | 0.893 | 0.775 |

The 64 KiB Java 25 saturated comparison gained about 14% throughput but had
about 4.5% higher p99. A follow-up holds offered load at 25,000 requests/s
for both implementations, using five seconds warm-up and ten measured seconds:

| Java | Original responses/s | Selected responses/s | Original p99 ms | Selected p99 ms | Original CPU µs/response | Selected CPU µs/response |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 21 | 25,000 | 25,000 | 0.454 | 0.432 | 118.4 | 110.9 |
| 25 | 25,000 | 25,000 | 0.435 | 0.489 | 119.8 | 110.0 |

The shorter fixed-rate latency difference was uneven between repeats. A
separate control with ten seconds warm-up and fifteen measured seconds
did not reproduce it; both JDKs had lower selected-NIO p99:

| Java | Original p99 ms | Selected p99 ms | Original CPU µs/response | Selected CPU µs/response |
| --- | ---: | ---: | ---: | ---: |
| 21 | 0.500 | 0.465 | 124.9 | 109.7 |
| 25 | 0.514 | 0.382 | 123.3 | 111.9 |

These phases are not pooled. The differing tails limit any conclusion from
the shorter result; GC time also varied, without establishing causation.

An isolated scheduling variant only yielded after a large TLS acknowledgement
when less than 16 KiB of the visit allowance remained. At the same 64 KiB
fixed offered rate, with ten seconds warm-up and fifteen measured seconds:

| Java | Selected p99 ms | Variant p99 ms | Selected CPU µs/response | Variant CPU µs/response |
| --- | ---: | ---: | ---: | ---: |
| 21 | 0.481 | 0.454 | 111.7 | 109.2 |
| 25 | 0.409 | 0.441 | 108.0 | 109.5 |

Discarded: The extra remaining-allowance condition did not show a consistent benefit. Java 21 median p99 fell about 5.7% with overlapping ranges, but Java 25 p99 rose about 7.7% and CPU rose about 1.4%. The longer original-versus-selected control did not reproduce the earlier short-run 64 KiB latency penalty, so the added condition is not justified.

At the matched 4,000 responses/s offered rate, HTTPS observations were:

| Java | Implementation | p99 ms | CPU µs/response | Cgroup measurement peak MiB |
| --- | --- | ---: | ---: | ---: |
| 21 | mu3 | 1.323 | 446.2 | 258.8 |
| 21 | mu4-blocking | 1.280 | 553.2 | 104.3 |
| 21 | original NIO | 1.979 | 616.3 | 109.2 |
| 21 | selected NIO | 1.342 | 458.4 | 114.0 |
| 25 | mu3 | 1.708 | 520.7 | 229.4 |
| 25 | mu4-blocking | 1.305 | 573.9 | 99.2 |
| 25 | original NIO | 1.913 | 602.4 | 102.9 |
| 25 | selected NIO | 1.186 | 435.5 | 111.0 |

At the 4,000/s offered rate, selected NIO cgroup peaks were higher by about
5 MiB on Java 21 and 8 MiB on Java 25. The analytic shared-buffer cost does
not explain the entire observed process-memory increase. These short-run
peaks also depend on runtime/GC and kernel state; allocation rate and retained
process memory are distinct measurements. Boundary RSS is included in the
HTML and JSON as a separate observation.

Direct original-versus-selected fairness controls:

| Probe | Java | Original p99 ms | Selected p99 ms | Selected dispatch p99 ms | Original bulk/s | Selected bulk/s |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| HTTPS, competing connections | 21 | 1.628 | 1.133 | 0.195 | 1598 | 2553 |
| HTTPS, competing connections | 25 | 1.513 | 1.155 | 0.274 | 1617 | 2543 |
| HTTP/2, shared connection | 21 | 1.529 | 1.526 | 0.129 | 400 | 401 |
| HTTP/2, shared connection | 25 | 1.534 | 1.531 | 0.125 | 400 | 401 |

Separate sampled allocation profiles of the final source estimated:

| Java | Original bytes/response | Selected bytes/response | Change |
| --- | ---: | ---: | ---: |
| 21 | 107,607 | 104,439 | -2.9% |
| 25 | 98,166 | 95,224 | -3.0% |

These are JVM allocation estimates per 1 MiB HTTPS response. They exclude
native memory and should not be treated as exact allocation accounting.
The fixed owner-shared buffer cost is reported separately above.

Large-response isolation uses the same 8 MiB shared fixture arrays in both
controls, with eight foreground clients downloading 1 MiB responses:

| Java | Stopped readers | Original responses/s | Selected responses/s | Selected p99 ms | Selected write-family calls/response |
| --- | ---: | ---: | ---: | ---: | ---: |
| 21 | 0 | 5,683 | 7,961 | 1.617 | 12.2 |
| 21 | 32 | 5,877 | 5,843 | 1.860 | 67.9 |
| 25 | 0 | 5,741 | 8,129 | 1.563 | 12.3 |
| 25 | 32 | 5,717 | 5,718 | 2.011 | 67.9 |

A stopped peer can retain an owner’s shared buffer. Other large writes then
use the ordinary record buffer, so the batching gain depends on the shared
buffer being available. This keeps retained memory bounded per owner.

A final test review strengthened the pending-ciphertext renegotiation/key-update
case to require completion of the transition and fresh traffic in both directions.
Production code was unchanged. The full suites below preceded that assertion
tightening; the subsequent 65-test TLS runs validate the strengthened test on
all four JDKs, with source and compiled-test digests checked.

The stopped-reader checks retain 32 pending 8 MiB HTTPS responses while four
foreground clients request 128-byte responses at 2,000/s. Both editions must
retain the stalled cohort, meet the offered foreground rate and recover after
cancellation. The small-window HTTP/2 control advertises 1,023 bytes per stream
over one connection with eight streams. Their full observations remain separate
from bulk throughput in the portable results.

## Validation

- `release-full-java21`: 5,531 tests, 0 failures, 0 errors, 0 skipped.
- `release-full-java25`: 5,531 tests, 0 failures, 0 errors, 0 skipped.
- `release-compatibility-11`: 377 tests, 0 failures, 0 errors, 0 skipped.
- `release-compatibility-17`: 377 tests, 0 failures, 0 errors, 0 skipped.
- `release-tls-review-java21`: 65 tests, 0 failures, 0 errors, 0 skipped.
- `release-tls-review-java25`: 65 tests, 0 failures, 0 errors, 0 skipped.
- `release-tls-review-java11`: 65 tests, 0 failures, 0 errors, 0 skipped.
- `release-tls-review-java17`: 65 tests, 0 failures, 0 errors, 0 skipped.

The final audit passed 8,612 checks across 422 successful native
trials and 68 fairness trials. Native logs reconcile 105,232,807
successful measured responses; fairness probes hash 464,000 measured
foreground bodies. All 493 owned JVM units are inactive, with native generators
reaped. An aborted initial profiler setup and the deliberate cleanup failure are
retained as evidence but excluded from the performance summaries.

The final source and compiled classes match the frozen selected candidate.
Changed production classes are confined to the TLS driver, its shared buffer
and transport integration; no experimental HTTP/2 grouping remains.
