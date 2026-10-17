# Three-way NIO baseline

Compare Mu3/Netty, pre-migration Mu4-blocking and optimized Mu4-NIO with one
application fixture, source/class/dependency inventories and a native h2load
generator. Production code and public async queue policy are outside this runner.

The short profile checks that the comparison works while the host is in use.
Every busy-host or smoke measurement is explicitly excluded from performance
conclusions, even when its correctness checks pass. Raw throughput, latency and
resource observations remain visible. A later quiet run goes in a new directory;
it never replaces the busy-host evidence.

## Workloads and controls

Each runtime runs six existing workloads: small HTTP/1 responses, fixed HTTP/1
bulk, HTTPS bulk, TLS HTTP/2 bulk, chunked HTTP/1 bulk, and multiplexed small
HTTP/2 responses. Bulk is 1 MiB over eight connections, using 8 KiB application
writes. Small HTTP/1 uses 32 connections; multiplexed HTTP/2 uses one connection
and 32 streams.

Isolation pairs run four foreground clients targeting 200 responses/s in smoke
mode (2,000 in full mode), first alone and then alongside paused 8 MiB downloads.
HTTPS and HTTP/2 are included by default; plaintext HTTP/1 is also supported.
HTTP/1 peers stop reading after response headers. HTTP/2 peers advertise zero
stream receive credit. The paused connections are distinct from the foreground
connections: this measures connection isolation, not fairness among streams on
one connection. The native request latency is not an independently validated
arrival-to-completion open-loop latency curve.

Paused peers must remain established during measurement and final verification.
Before cancellation, HTTP/1 peers must still have queued TCP output, and every
HTTP/2 stream must resume when granted exactly one byte of credit. A fresh small
response must succeed after cancellation. Foreground connection accounting
subtracts only the verified paused cohort, retaining the original raw counts.

Mu4 uses explicit PLATFORM workers on Java 21 and VIRTUAL workers on Java 25.
Mu3 retains its Netty configuration. `/mode` checks actual handler worker mode and
transport before each trial. Thus the Java 21 NIO default is compared with a
matched blocking worker policy; this is not the historical blocking AUTO policy.

Every trial starts a fresh JVM with a 512 MiB heap, 1 GiB cgroup memory cap and
swap disabled. Defaults assign server CPUs `0-3,8-11` and generator CPUs
`4-7,12-15`, matching the earlier host topology. CPU lists are configurable.
Concurrent desktop use can still affect scheduling, frequency, caches and memory
bandwidth. Host load, CPU counters and pressure snapshots are recorded; they do
not turn a busy-host run into a controlled comparison.

Payload hashes are checked before and after load. Native request/status logs are
reconciled during load. Generator saturation, incomplete connections, missed
foreground rate and correctness/resource failures are recorded. A failed trial
stops its run; keep that evidence and use a new run ID after a fix.

## Run from the shared workspace

Prerequisites are the sibling `mu-conformance` virtual environment, the installed
native h2load tool, Java 21/25 and the preserved October 14/17 benchmark artifacts.
The default input editions are Mu3 `78f02551`, Mu4-blocking `50ad1a70` and the
optimized NIO production source matching `dc83a847`. `--nio-build` accepts another
verified NIO `build.json` for a later experiment. Preparation validates the input
inventories, copies production class directories, and recompiles the same fixture
source against all three editions. Original evidence is never edited. Runtime
jars and source snapshots remain referenced by path and are revalidated.

```bash
mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/baseline.py prepare \
  --out nonblocking-validation/baseline-20261017-busy

mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/baseline.py run \
  --out nonblocking-validation/baseline-20261017-busy \
  --run-id smoke-busy-01 --profile smoke --host-state busy
```

Smoke uses one second of warm-up, three seconds of measurement, eight paused
clients and one repeat. The default matrix has 60 short trials across Java 21/25.
Server startup, protocol verification and teardown add time outside measurement.

When the host is available for a controlled run, reuse those same frozen inputs:

```bash
mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/baseline.py run \
  --out nonblocking-validation/baseline-20261017-busy \
  --run-id quiet-01 --profile full --host-state quiet
```

Full uses ten seconds of warm-up, twenty seconds of measurement, 128 paused peers
and three repeats with cyclic edition order. It is a substantially longer run.
`--paused-connections`, `--repeats` and `--mixed-protocols` allow explicit changes;
the exact specification is retained with results. Choose a new ID for every run.

The report and raw ledger are at `OUT/runs/RUN_ID/report.html` and `results.json`.
Every trial also retains generator logs, server/cgroup samples, launch settings,
mode checks and source metadata. The runner checks frozen identities again after
the final trial. Source and compiled classes were previously tested; this command
does not rebuild or rerun the production test suite.

Run the harness's focused checks from this directory:

```bash
python -m unittest -v test_baseline
```

These checks cover exclusion rules, worker matching, paired workload construction,
edition-order rotation, missing paused peers, unsuitable paused responses and
report preservation of failures. Live smoke runs exercise the native generator,
socket/TLS/H2 peers, memory-limited service lifecycle and cancellation recovery.

## Initial busy-host smoke result

`baseline-20261017-busy/runs/smoke-busy-01` completed all 60 default trials in
approximately five minutes, with 3,954,646 measured foreground responses and zero
request errors. All 24 fixed-rate isolation controls met their target; all 12
paused-client trials verified eight pending downloads and successful recovery
after cancellation. No generator-saturation or incomplete-connection flags were
recorded. All 60 measurements remain excluded from performance conclusions
because the PC was in use and the runs were deliberately short. The nine focused
harness checks passed. The quiet-host comparison below uses a separate evidence
directory and newer frozen NIO production classes.

## October 18 quiet-host comparison

The evidence root beside the checkout is
`nonblocking-validation/baseline-20261018-quiet/`. Its combined `report.html`
links individual trials, source/class/dependency manifests, native logs, resource
samples, exclusions and `summary.json`. The production editions are:

| Edition | Commit |
| --- | --- |
| Mu3 / Netty | `78f025511e4edbaf5681ece0159967fc40d12ecb` |
| Pre-migration Mu4 blocking | `50ad1a7072fffe959770531204a09b2ddd3b4637` |
| Mu4 NIO | `75ce9e7cf1def76d17c9945d00245a366ed09289` |

The NIO production source digest is
`c764d37da77d10f8ba9ea6ee1a020955987a113b25474d981bd3f2b88995b5b0`.
Its frozen snapshot retains pre-commit Git metadata; the digest matches the
committed checkout. Blocking is the preserved pre-migration implementation,
not a transport toggle on the current branch.

Every full trial uses a fresh JVM, ten seconds of warm-up and twenty seconds
of measurement. The host is an AMD Ryzen 9 8945HS (eight cores, sixteen logical
CPUs), using Temurin `21.0.12.1+1` and `25.0.4.1+1`. Three repeats rotate edition
order. Java 21 and Java 25 use the worker policies, CPU sets, 512 MiB heap and
1 GiB cgroup described above.
The primary profiles let the JVM choose its collector; the actual runtime
collector is Serial under this memory cap. Separate controls explicitly select
G1 with the same resource limits. Java 21 versus Java 25 comparisons also change
Mu4's worker policy, so their difference cannot be attributed solely to the JDK.

The extended campaign separates application write styles:

* Synchronous output uses 8 KiB writes, including the original six workloads
  and 128-paused-client isolation controls: 180 full trials.
* Bounded async bulk submits one stable shared-array write per 1 MiB response.
  Additional cases cover multiplexed bulk, high/low-compressibility gzip,
  raw uploads, multipart uploads and 256 small-response connections: 180 trials.
  Uploads use the blocking `InputStream`/`uploadedFiles` application APIs.
* Callback-driven streaming submits 32 KiB at a time, with one application
  write awaiting completion per request. Isolation controls use 128 paused
  HTTPS or HTTP/2 connections: 72 trials. A separate HTTP/2 control uses
  768 paused connections under the same resource limits: 36 trials. A separate
  1,024-client default-limit probe is retained too: 36 trial attempts.
* Explicit G1 controls repeat small HTTP/1, bounded async HTTP/1 bulk and bounded
  async HTTP/2 bulk: 54 trials. A matched Serial-GC small-response control uses
  the exact async fixture too: 18 trials. Bulk already has matching Serial trials.
* A separate Java 25 raw HTTP/2 upload confirmation repeats all three editions
  three times: nine trials. Only post-measurement shutdown observation changes.

The matrix includes 585 full-profile trial attempts. Twelve streaming preflights
fully verify 8 MiB bodies across both JDKs, HTTPS/1 and HTTP/2 over TLS 1.3,
and all editions; their short load measurements are excluded from speed
comparisons. No producer
queues unbounded output. The supplemental controllers, fixture sources, exact
commands and frozen build inventories are retained in the evidence root.

Bulk uses eight connections; multiplexed bulk uses one connection/eight streams.
TLS is TLS 1.3 AES-128-GCM. HTTP/2 uses a 65,535-byte stream receive window and
16,777,215-byte connection window. Isolation targets 2,000 foreground responses/s
over four clients, with paused downloads on other connections. Every paused
cohort must remain intact, pending HTTP/2 responses must resume on one byte of
credit, and fresh requests must work after cancellation.

Both Mu4 editions default to 1,000 concurrent requests; Mu3 lacks that admission
setting. The 1,024-client probe receives HTTP 503 during Mu4 setup. Those rows
remain failed/excluded, rather than being interpreted as transport capacity.
The additional 768-client control uses identical frozen application code and
defaults, keeping the paused cohort plus foreground traffic below that limit.
No per-edition admission limit or worker setting is retuned for it.

The final audit also found one original Java 25 Mu3 raw HTTP/2 upload trial whose
saved systemd snapshot preceded forced cleanup. The server emitted `STOPPED`,
but the harness later terminated the process; the unit journal records exit 143.
That original record remains unchanged and is excluded through
`post-audit-findings.json`. The nine-trial confirmation uses the same frozen
application and measurement settings, allows up to ten seconds for post-load
JVM exit, captures threads if exit stalls, and records final status before
`reset-failed`. All nine confirmation trials passed with normal JVM exits.
This observation does not establish the cause of the original shutdown delay.

Within-row comparisons use identical fixtures. Combined-report rate tables show
medians and observed ranges; percentage changes are medians of three within-repeat
NIO/reference changes, requiring all three valid pairs. Native latency is
closed-loop request latency, not a validated open-loop arrival-to-completion
curve. These are fixed-concurrency workloads rather than exhaustive capacity
sweeps. Three repeats describe observed variability, not confidence intervals.

Charged memory includes heap, native allocations, socket buffers and kernel
accounting. Lifetime cgroup peaks and sampled measurement-period anonymous/socket
components are reported separately. Anonymous memory is not a heap measurement,
and these observations do not measure allocation rates. Native thread counts
are sampled OS-thread maxima; virtual threads are not OS threads.

### Synchronous throughput

Median responses/s over three repeats, using 8 KiB application writes for bulk.
HTTP/1 small responses use 32 connections; HTTP/2 small responses use one
connection and 32 streams. Bulk uses eight connections.

| Workload | J21 Mu3 | J21 blocking | J21 NIO | J25 Mu3 | J25 blocking | J25 NIO |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Small HTTP/1, 128 B | 165,488 | 179,039 | 121,371 | 174,789 | 244,831 | 213,706 |
| HTTP/1 bulk, 1 MiB | 2,218 | 6,881 | 3,022 | 2,229 | 6,582 | 3,185 |
| HTTPS/1 bulk, 1 MiB | 1,937 | 5,309 | 2,280 | 1,856 | 5,147 | 2,377 |
| TLS HTTP/2 bulk, 1 MiB | 1,730 | 1,822 | 1,234 | 1,609 | 2,895 | 1,737 |
| Chunked HTTP/1 bulk, 1 MiB | 1,984 | 4,519 | 2,923 | 1,968 | 4,278 | 3,080 |
| Small TLS HTTP/2, 128 B | 80,404 | 170,702 | 141,388 | 78,465 | 177,562 | 157,681 |

Blocking Mu4 leads all six synchronous cases on each JDK. NIO exceeds Mu3 in
all six on Java 25, and four on Java 21. This ranking is specific to these
application write sizes and workloads; the bounded async results exercise a
different write pattern. Full observed ranges, paired changes, p95/p99 latency
and CPU/resource data remain in the combined report.

### Bounded async, compression and upload throughput

Median responses/s over three valid repeats. Bulk uses one bounded 1 MiB async
write per response. Upload handlers use blocking application APIs. Gzip inputs
are 64 KiB; the separate small-response case uses 256 connections.

| Workload | J21 Mu3 | J21 blocking | J21 NIO | J25 Mu3 | J25 blocking | J25 NIO |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| HTTP/1 bulk | 18,523 | 17,806 | 4,549 | 18,835 | 18,021 | 4,885 |
| HTTPS/1 bulk | 7,916 | 7,705 | 3,683 | 7,459 | 7,861 | 3,852 |
| TLS HTTP/2 bulk | 2,819 | 2,010 | 2,551 | 2,759 | 2,514 | 2,744 |
| TLS HTTP/2 bulk, one connection/eight streams | 852 | 755 | 830 | 832 | 750 | 795 |
| HTTP/2 gzip, high compressibility | 22,548 | 16,942 | 14,227 | 21,884 | 19,691 | 18,010 |
| HTTP/2 gzip, low compressibility | 4,370 | 3,620 | 3,133 | 4,328 | 4,031 | 3,376 |
| Raw HTTPS/1 upload, 1 MiB | 2,728 | 5,701 | 3,533 | 2,642 | 6,039 | 3,664 |
| Raw HTTP/2 upload, 1 MiB | 2,596 | 2,478 | 1,567 | 2,512 | 2,517 | 1,811 |
| Multipart HTTP/2 upload, 1 MiB file | 1,049 | 1,376 | 1,014 | 1,026 | 1,439 | 1,194 |
| Small HTTP/1, 128 B / 256 connections | 153,969 | 134,597 | 107,055 | 153,058 | 304,671 | 260,779 |

The Java 25 raw HTTP/2 upload row uses the separately identified confirmation
profile. The original group remains visible in the report with one Mu3 cleanup
exclusion and no three-pair NIO/Mu3 comparison.

Application API and write pattern materially change the ranking. Java 25 NIO
exceeds Mu3 in synchronous HTTP/1 bulk, but remains roughly 74% behind Mu3
when both submit a single bounded 1 MiB async write. Blocking Mu4 is also far
ahead of NIO in that large-write HTTP/1 case. The gap persists under G1.

For bounded async HTTP/2 bulk, median paired NIO throughput is 8.7% below Mu3
and 27.1% above blocking on Java 21; on Java 25 it is 1.1% below Mu3 and 8.6%
above blocking. The three Java 25 NIO/Mu3 changes are -4.5%, -1.1% and +4.7%,
so a small difference in median rate should not be treated as a stable winner.

### HTTP/2 bulk CPU, latency and memory

Bounded async 1 MiB responses over eight connections, primary Serial-GC profile.
These latency values are measured at each edition's own achieved load.

| JDK | Edition | p99 ms | Server CPU µs/response | Charged peak MiB |
| --- | --- | ---: | ---: | ---: |
| 21 | Mu3 | 3.817 | 991 | 852.3 |
| 21 | Mu4 blocking | 4.545 | 2,580 | 108.3 |
| 21 | Mu4 NIO | 4.013 | 2,121 | 136.2 |
| 25 | Mu3 | 3.481 | 1,042 | 807.6 |
| 25 | Mu4 blocking | 4.020 | 1,988 | 115.7 |
| 25 | Mu4 NIO | 4.028 | 2,063 | 138.5 |

NIO's much lower charged memory than Mu3 in this case comes with approximately
twice the server CPU per response and higher p99 latency. Similar throughput
does not mean equal CPU efficiency or equal tail latency. These memory
observations are not allocation-rate or heap-only measurements.

### Collector controls

G1 median responses/s, using the same async fixture as its Serial controls.

| Workload | J21 Mu3 | J21 blocking | J21 NIO | J25 Mu3 | J25 blocking | J25 NIO |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Small HTTP/1, 128 B / 32 connections | 207,316 | 213,289 | 141,930 | 208,038 | 266,756 | 245,799 |
| Bounded async HTTP/1 bulk | 18,417 | 17,971 | 4,566 | 18,530 | 18,158 | 4,939 |
| Bounded async TLS HTTP/2 bulk | 2,964 | 2,139 | 2,672 | 2,874 | 2,687 | 2,904 |

For the exact same small-response fixture, the Serial-GC medians are:

| Workload | J21 Mu3 | J21 blocking | J21 NIO | J25 Mu3 | J25 blocking | J25 NIO |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Matched Serial small HTTP/1 | 168,223 | 177,124 | 121,512 | 171,316 | 244,515 | 213,234 |

Collector choice materially affects small-response rates and charged memory,
but it does not remove the large bounded-async HTTP/1 throughput gap. G1
HTTP/2 bulk on Java 25 again leaves NIO and Mu3 close in throughput. This is
a settings control; evaluate charged memory alongside any rate gain.

### Slow clients and application API

Callback-driven 32 KiB output, with one write awaiting completion per request.
All 128- and 768-client controls met the 2,000/s foreground target. Native-thread
figures are medians of sampled measurement maxima; charged peak includes startup
and verification. Each cell uses three valid repeats.

| JDK | Paused H2 clients | Edition | Native threads alone → paused | p99 ms alone → paused | Charged peak MiB alone → paused |
| --- | ---: | --- | ---: | ---: | ---: |
| 21 | 128 | Mu3 | 29 → 38 | 0.194 → 0.202 | 220.6 → 344.2 |
| 21 | 128 | Mu4 blocking | 31 → 284 | 0.283 → 0.186 | 106.3 → 157.8 |
| 21 | 128 | Mu4 NIO | 36 → 40 | 0.204 → 0.203 | 114.1 → 137.7 |
| 25 | 128 | Mu3 | 29 → 38 | 0.210 → 0.206 | 211.5 → 279.7 |
| 25 | 128 | Mu4 blocking | 25 → 25 | 0.201 → 0.204 | 112.8 → 113.4 |
| 25 | 128 | Mu4 NIO | 34 → 36 | 0.205 → 0.204 | 102.9 → 114.9 |
| 21 | 768 | Mu3 | 29 → 38 | 0.205 → 0.202 | 203.2 → 352.7 |
| 21 | 768 | Mu4 blocking | 28 → 1564 | 0.193 → 0.203 | 121.8 → 377.1 |
| 21 | 768 | Mu4 NIO | 35 → 43 | 0.204 → 0.201 | 108.1 → 299.3 |
| 25 | 768 | Mu3 | 29 → 38 | 0.187 → 0.207 | 248.9 → 311.1 |
| 25 | 768 | Mu4 blocking | 25 → 25 | 0.200 → 0.204 | 107.3 → 158.1 |
| 25 | 768 | Mu4 NIO | 34 → 36 | 0.203 → 0.205 | 117.7 → 211.1 |

On Java 21, the bounded async producer keeps NIO and Mu3 native-thread counts
nearly flat while blocking Mu4 reaches 1,564 native threads at 768 paused clients.
All three meet the offered rate, with paused p99 near 0.2 ms. On Java 25, blocking
Mu4 also keeps native threads flat and uses less charged memory than NIO in
this case (158.1 versus 211.1 MiB).

Synchronous application output changes the thread result. With 128 paused H2
downloads on Java 21, sampled native-thread medians are 162 / 282 / 169 for
Mu3 / blocking / NIO. The callback producer changes those to 38 / 284 / 40.
NIO transport cannot make a blocking application write release a platform worker.
On Java 25 the corresponding Mu4 synchronous medians remain 25 / 36 because
its workers are virtual; Mu3 synchronous output still grows to 161 native threads.

Paused HTTPS/1 also fills kernel socket buffers: approximately 320 MiB of
measurement-period socket charge at 128 clients for every edition. Treating
that entire charged footprint as JVM heap would misidentify the memory cost.

### Validation and evidence

All 585 trial attempts are retained, covering 361,959,904 measured
responses. There are 572 valid timed trials and thirteen exclusions: twelve
expected Mu4 admission-limit setup rejections and the original Mu3 cleanup
failure identified from its unit journal. There were no native timed request
errors. All nine upload-confirmation trials exited normally. The twelve short
streaming preflights also passed full 8 MiB payload checks and remain excluded
from performance conclusions.

The evidence audit passed 8,929 checks, including complete matrices, frozen
source/class/dependency/generator identities, matched fixtures and limits, actual
worker/GC modes, payload checks, retained paused cohorts, cancellation recovery,
process termination and temporary-file cleanup. The original stale shutdown
snapshot is preserved alongside its journal and explicit post-audit exclusion.
The nine focused baseline-harness checks passed before the campaign. Production
code was held constant; the production test suite was not rerun for this
measurement/documentation task.

The combined report and `summary.json` are at
`nonblocking-validation/baseline-20261018-quiet/` beside the checkout. Raw
ledgers, individual result bundles, executed controllers, frozen manifests,
`audit.json`, `post-audit-findings.json` and the cleanup journal are linked
from the report. No busy-host measurements or JFR diagnostic rates enter this
quiet-host comparison. Fixed connection counts and three repeats do not establish
a universal capacity limit, an open-loop latency SLA or a confidence interval.
