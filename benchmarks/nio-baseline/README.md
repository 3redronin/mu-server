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

## Idle keep-alive connection density

`connection_density.py` adds a different concurrency workload: many completed,
idle HTTP/1 connections alongside separate active foreground connections. It
does not leave thousands of application requests open, so the default Mu4
1,000-concurrent-request limit does not constrain this population.

The full profile compares zero, 1,024 and 4,096 idle connections over plaintext
and TLS 1.3, on Java 21 and 25, with all three editions and three cyclic repeats:
108 trials. Every JVM has a 256 MiB maximum heap and a 512 MiB whole-server cgroup,
with swap disabled and the same separate server/generator CPU sets as the earlier
baseline. Mu4 keeps the matched PLATFORM/21 and VIRTUAL/25 policies. Five seconds
of warm-up precede ten measured seconds. Sixteen foreground connections target
10,000 exact 128-byte responses/s; rates below 98% of target are excluded.

Each idle connection first completes a fully checked response. The runner
verifies that the entire cohort remains established at measurement checkpoints,
then requires another exact response on every connection after timing. Finally
it closes the cohort, verifies fresh-request health and observes normal JVM exit.
Failed or partial setups remain visible without being counted as successful
capacity measurements. Shutdown status is captured before resetting the unit;
an unsuccessful JVM exit cannot be hidden by a successful `systemd-run` launcher.

The runner raises only its own soft descriptor limit within the existing hard
limit, records that budget, and checks the fixture JVM's descriptor limit before
opening the cohort. It does not change desktop or system configuration. This
prevents a client-side descriptor ceiling from masquerading as server capacity.

Run against any compatible prepared baseline input directory, using a new ID:

```bash
mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/connection_density.py \
  --inputs nonblocking-validation/baseline-20261018-quiet/sync \
  --run-id density-full-new --profile full --host-state quiet
```

`--profile smoke` defaults to 64 idle TLS connections plus the zero-connection
control, one second of warm-up and three measured seconds. `--counts`,
`--protocols` and `--jdks` select explicit subsets; zero must remain as a control.
`--heap 384m` applies a common larger heap to every edition while retaining the
default 512 MiB total cgroup cap. This can distinguish a heap-distribution limit
from whole-server memory exhaustion when implementations place buffers in
different memory pools. It is a separate control, not a replacement for the
default 256 MiB-heap results.

Smoke/busy-host rates cannot supply performance conclusions. Reports, raw trial
bundles, the executed controller, specification and hardware snapshots go in
`INPUTS/runs/RUN_ID/`. The frozen input inventories are verified before and after
the run. This supplements throughput tests with resource-density and foreground
isolation evidence; it does not promise that NIO will win on every JDK or protocol.

Run the focused framing, cohort, descriptor-budget, shutdown, generator-lifecycle
and matrix checks from `mu4/benchmarks/nio-baseline/`:

```bash
python -m unittest -v test_baseline test_connection_density
```

### Quiet-host density results, 18 October 2026

The 108-case default-heap matrix completed with 96 valid timed trials. All
zero- and 1,024-idle-connection cases passed. At 4,096 idle connections, the
following medians summarize three repeats; every successful run sustained the
10,000/s foreground target and checked every idle connection after timing.
Memory is sampled peak cgroup charge during measurement, not heap usage.

| Java / transport | Mu3: native threads / MiB | Mu4 blocking: native threads / MiB | Mu4 NIO: native threads / MiB |
| --- | ---: | ---: | ---: |
| 21 / plaintext HTTP/1 | 54 / 219.8 | Whole-server OOM, 0/3 | 65 / 320.0 |
| 21 / TLS HTTP/1 | 45 / 325.5 | Whole-server OOM, 0/3 | Java heap exhausted, 0/3 |
| 25 / plaintext HTTP/1 | 53 / 198.5 | 25 / 173.6 | 28 / 234.1 |
| 25 / TLS HTTP/1 | 41 / 268.8 | 25 / 217.2 | Java heap exhausted, 0/3 |

This supplies a concrete Java 21 NIO resource-density win against platform-thread
blocking. In the plaintext blocking failures, all 4,096 idle sockets had been
opened, but the JVM could not sustain the separate foreground traffic within
the same total budget. The TLS blocking failures occurred during cohort setup.
Mu3 also scales well in this workload, with lower measured memory than NIO.
Java 25 virtual-thread blocking sustains both populations at lower measured
memory than NIO. These tests do not demonstrate a universal NIO speed or
latency win.

The NIO TLS failures expose a real heap-distribution weakness: at the default
256 MiB heap, neither JDK can establish the entire 4,096-connection TLS cohort,
although lifetime charged memory remains below the total 512 MiB limit. These
runs are preserved as failures, with no invented throughput or capacity number.

The separate common-384-MiB-heap control keeps the whole-server limit at
512 MiB. On Java 21, NIO retains and resumes all 4,096 TLS connections in all
three repeats, but misses the foreground target in all three: median
7,683 responses/s, 62.121 ms p99, and 6,078 ms of recorded GC time per measured
ten seconds. Its observed rate ranges from 4,844 to 7,716/s. These measurements
remain visible as excluded diagnostics, not valid target-rate results. Median
measurement charge is 483.7 MiB; median lifetime peak is 491.1 MiB. Increasing
the heap alone does not resolve the TLS resource problem.

An audit found two generator-lifecycle overlaps in the original 384 MiB control:
an early fixture-stats exception could bypass the frozen harness's generator
cleanup. That original 36-case control is retained as diagnostic evidence only.
The entire control was rerun after adding an outer guard that tracks and reaps
only the trial's native generator, waits for its log reader, and refuses another
trial if cleanup cannot finish. Normal exit, early failure, forced termination,
kill escalation and incomplete reader cleanup have focused regression checks.
The original measured ledgers and frozen harness were not edited.

In the clean larger-heap control, Java 25 NIO still exhausts Java heap in all
three 4,096-idle-TLS trials. Mu3 meets the target on both JDKs; virtual-thread
blocking meets it on Java 25, while platform-thread blocking still reaches the
whole-server memory limit on Java 21. Java 25 blocking's median measurement
charge is 217.1 MiB, versus Mu3's 307.6 MiB; its median p99 is 0.346 ms versus
Mu3's 0.527 ms. These fixed-rate observations do not establish maximum throughput.

The final comparison comprises 144 clean full-trial attempts: 123 valid
measurements, eighteen resource failures and three missed-target observations,
with 12,502,560 measured responses retained. The audit passed 3,736 checks across
204 preserved records: those 144 attempts, the corrected 24-case smoke matrix,
and the superseded 36-case diagnostic control. The original twelve-case
client-descriptor-limited pilot is also retained separately. All 22 focused
Python checks passed. No Java production code changed.

The combined local report is
`nonblocking-validation/idle-density-20261018-report.html` beside the checkout.
It links the per-profile reports, frozen manifest, raw ledgers, executed
controller snapshots, audit and post-audit findings. The clean larger-heap
control is `density-heap384-20261018-02`; the `-01` control is diagnostic only.
Observed excluded rates, latency and GC remain visible alongside their exclusion
reasons. The evidence identifies idle TLS memory as a concrete next optimization
target, rather than assuming the plaintext scaling result extends to TLS.


## Equal total memory with independent heap sizes

A common `-Xmx` is a useful configuration control, but does not give identical
usable buffer capacity to heap-based NIO and Netty's off-heap allocator. The
whole-server cgroup charge includes both; it remains the authoritative budget.
A Java heap failure below that budget establishes a configuration limit, not an
inherent total-memory requirement.

`connection_density.py` now accepts `--cap-mib 512` or `1024`, a whole-MiB
`--heap` value, and `--heap-for JDK/edition=SIZE` overrides. The heap maximum must
fit within the total cap. `--direct-memory-mib` supplies the same explicit JVM
direct-memory ceiling to every edition independently of heap size. This is an
allocation ceiling, not a reservation or additional memory allowance: all heap,
native, direct and kernel memory still share the one cgroup, with swap disabled.
No buffer implementation or collector policy is changed by these options.

The equal-budget experiment uses both total caps. Every edition/JDK tests heaps
of one quarter, one half, three quarters and all of the total cap, with the direct
ceiling equal to that cap. Each candidate has zero- and 4,096-idle-TLS controls,
sixteen foreground connections and the same 10,000/s offered rate. The 96 tuning
trials use `--phase tuning --profile smoke`; their short rates are excluded from
performance conclusions.

For each edition/JDK, the selection rule prefers the smallest heap that completes
the full cohort and meets 98% of the target without detected generator
saturation. If none does, it selects the best correctly completed rate; if all
fail correctness/resources, it selects the largest established cohort. Ties
prefer smaller heaps. A candidate must also have a successful zero-cohort
control when available. This is a bounded heap search for this workload, not a
claim of globally optimal JVM tuning.

The selected heaps are frozen before the independent
`--phase confirmation --profile full` runs: three cyclic repeats, five seconds
of warm-up and ten measured seconds, including fresh zero-cohort controls. Both
total caps remain separate comparisons. Confirmation failures are retained;
selection does not make an unsuccessful tuning candidate count as successful.
For example, a fresh confirmation can use:

```bash
mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/connection_density.py \
  --inputs nonblocking-validation/baseline-20261018-quiet/sync \
  --run-id tls-independent-new --phase confirmation --profile full --host-state quiet \
  --counts 0 4096 --protocols https1 --cap-mib 1024 --heap 256m \
  --direct-memory-mib 1024 --heap-for 21/nio=768m 25/nio=768m
```

The heap overrides in that command are illustrative; the executed experiment
records all selected heaps, candidates, raw outcomes, commands and source hashes.


### Selected heaps for the 18 October equal-budget experiment

| Total server cap | Java | Mu3 heap | Mu4 blocking heap | Mu4 NIO heap |
| --- | --- | ---: | ---: | ---: |
| 512 MiB | 21 | 128 MiB | 128 MiB | 384 MiB |
| 512 MiB | 25 | 128 MiB | 256 MiB | 384 MiB |
| 1,024 MiB | 21 | 256 MiB | 256 MiB | 512 MiB |
| 1,024 MiB | 25 | 256 MiB | 256 MiB | 512 MiB |

Selection is not a success claim. At 512 MiB total, no Java 21 blocking or
NIO candidate sustains the full offered workload; their listed heaps follow
the documented fallback rule and must retain confirmation failures/exclusions.
At 1,024 MiB total, every edition/JDK has a successful tuning candidate.
The candidate ranges are 128/256/384/512 MiB and 256/512/768/1024 MiB,
respectively; this compares a shared search space within each total budget.

The independent 512 MiB confirmations retain the earlier dense-TLS outcome
after allowing different heaps. At 4,096 idle connections, Mu3 sustains the
10,000/s foreground target on both JDKs with a 128 MiB heap. Its median
measurement charge is 324.0 MiB on Java 21 and 265.4 MiB on Java 25. Java 25
blocking sustains the target with a 256 MiB heap at 216.1 MiB measurement charge.
Java 21 blocking, selected at 128 MiB, reaches the whole-server limit in all
three dense trials.

Java 21 NIO's selected 384 MiB heap retains and resumes the entire cohort, but
all three confirmations miss the rate target: median 7,538 responses/s,
62.650 ms p99, 6,066 ms recorded GC time per ten measured seconds and
492.4 MiB measurement charge. These are excluded diagnostic measurements.
Java 25 NIO's selected 384 MiB heap exhausts Java heap in all three dense trials.
The larger 512 MiB heap candidate reached the whole-server limit on both JDKs
during tuning. The bounded search therefore did not find a configuration that
sustains this dense workload within 512 MiB total; this is not proof that no
other heap layout, collector or implementation could do so.

### Deeper latency tails without changing the measured workload

`latency_tails.py` post-processes a completed run's native `h2load.json` sample
vectors. It validates the complete declared sample count, finite sorted values
and maximum, then computes nearest-rank p99, p99.9 and p99.99 in milliseconds.
The separate `latency-tails.json` overlay preserves per-trial exclusions and
reports medians of per-trial percentiles/maxima plus the worst observed maximum.
It never edits the measured ledger or profiles the running server. Run it after
the measurement controller finishes:

```bash
python mu4/benchmarks/nio-baseline/latency_tails.py \
  nonblocking-validation/baseline-20261018-quiet/sync/runs/RUN_ID
```

A low p99 can coexist with long rare pauses; the equal-budget report includes
these deeper tails and GC time. These remain native request latencies from the
closed-loop generator, rather than a validated open-loop latency SLA. Run all
focused checks from `mu4/benchmarks/nio-baseline/` with:

```bash
python -m unittest -v test_baseline test_connection_density test_latency_tails
```


### 1 GiB confirmations and timing qualification

All 36 confirmations at the same 1,024 MiB whole-server cap complete the payload,
connection-retention/resumption and cleanup checks, and meet the foreground
rate threshold. The dense TLS cases sustain approximately 10,000 responses/s
in all three repeats with 256 MiB heaps for Mu3/blocking and 512 MiB for NIO.
Thus an appropriate larger heap resolves NIO's heap-failure outcome at this
total budget without changing Java production code. The JVM-selected collectors
reported in these selected configurations are Copy/MarkSweepCompact (Serial GC);
this is a heap-budget experiment, not a collector-tuning comparison.

Concurrent desktop/development activity was reported during this confirmation;
its exact onset is uncertain. The entire 36-case 1 GiB confirmation is therefore
excluded from controlled timing/CPU ranking. Original declared host metadata
and measured ledgers remain intact, with explicit `post-audit-findings.json`
qualifications applied to both summary reports and deeper-tail overlays.
The following are observed medians of three dense trials; timing and CPU are
indicative only. Measurement memory is sampled peak total cgroup charge;
lifetime peak includes setup and resumption.

| Java | Edition | Heap max MiB | Native threads | Measurement MiB | Lifetime peak MiB | p99 ms | p99.9 ms | CPU µs/response |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 21 | Mu3 | 256 | 51 | 428.4 | 434.4 | 0.148 | 0.512 | 42.7 |
| 21 | Mu4 blocking | 256 | 4149 | 701.6 | 837.3 | 0.143 | 5.686 | 33.5 |
| 21 | Mu4 NIO | 512 | 72 | 625.4 | 630.3 | 0.374 | 74.736 | 72.9 |
| 25 | Mu3 | 256 | 39 | 352.7 | 365.6 | 0.185 | 0.884 | 44.5 |
| 25 | Mu4 blocking | 256 | 25 | 246.7 | 257.7 | 0.183 | 0.713 | 59.1 |
| 25 | Mu4 NIO | 512 | 36 | 612.9 | 620.8 | 0.172 | 77.176 | 66.0 |

The 1 GiB observations separate adequate heap headroom from memory efficiency.
NIO uses fewer native threads and less charged memory than Java 21 blocking,
while Mu3 and Java 25 blocking use less charged memory than NIO. JMX buffer-pool
readings confirm that a heap-only comparison omits a substantial part of Mu3's
buffer allocation; the total-charge columns include it.

NIO's observed p99 alone conceals the deeper tail: median per-trial p99.9 is
approximately 75–77 ms, and the worst observed request is 89.6 ms on Java 21 and
111.6 ms on Java 25. Recorded GC time is 722 and 1,050 ms per ten measured
seconds, respectively. These are indicative observations that warrant a
controlled follow-up; this campaign does not establish the cause of every
outlier. Full p99.99, maxima, per-run samples, zero-cohort controls and all
exclusions are retained. No maximum-throughput or open-loop latency claim is
made from the fixed offered load.

The campaign preserves 96 short tuning trials and 72 independent confirmation
attempts, with 6,526,220 measured confirmation responses. Sixty-six confirmations
complete correctness/cohort/cleanup verification; six have resource failures.
Of the 72 attempts, 27 are eligible performance observations, three miss the
rate target and 36 are classified as indicative because of concurrent desktop
activity. All 31 focused Python checks passed, including heap-budget selection,
complete-sample tail validation and late host-activity qualification without
rewriting raw evidence. The evidence audit passed 4,858 checks, including actual
memory limits/flags, unchanged frozen production inputs, cyclic ordering,
complete payload/cohort checks, generator reaping, reader completion and normal
JVM exit for successful runs. No Java production code changed.

The combined report is
`nonblocking-validation/tls-memory-20261018-report.html` beside the checkout.
It links the campaign specification, executed controller, frozen input manifest,
audit, host-activity qualification, raw trials and complete-sample tail overlays.
`tls-memory-20261018-summary.json` contains the combined machine-readable results.

## Short HTTP/2 regression probes

`roundness.py` adds three explicitly busy-host, rate-limited suites. It reuses
frozen Java production classes and existing fixtures; no Java changes or
unbounded application writes are involved.

* `multiplex`: 1 MiB async downloads over one connection with up to eight
  streams, comparing 65,535-byte and 1,023-byte stream receive windows. Raw
  1 MiB uploads compare one connection/up to eight streams against eight
  connections/one stream each. Every case offers 32 requests/s total. At this
  low paced rate, the stream limit does not ensure simultaneous requests.
* `burst`: the same cases submit eight requests every 250 ms (one per
  connection in the eight-connection case). Saved native request intervals
  must demonstrate at least four overlapping requests. This exercises
  multiplexing while keeping the average offered payload near 32 MiB/s.
* `fairness`: 200 small responses/s share one TLS HTTP/2 connection, first
  alone, then alongside 16 zero-credit 8 MiB downloads. The existing producer
  has one 32 KiB async write outstanding per response. The Python client grants
  only foreground stream credit and replenishes connection credit independently.
  Every small body is checked; each paused stream must subsequently resume for
  exactly one correct byte. All are cancelled, and the same connection plus a
  fresh connection must still serve correct responses.

Use the async fixture for native suites and the callback-streaming fixture for
fairness. Within each suite, all three editions use identical application code.
For example, from the shared workspace:

```bash
mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/roundness.py \
  --inputs nonblocking-validation/baseline-20261018-quiet/async \
  --suite burst --run-id rounded-burst-new --repeats 2

mu-conformance/.venv/bin/python mu4/benchmarks/nio-baseline/roundness.py \
  --inputs nonblocking-validation/baseline-20261018-quiet/streaming \
  --suite fairness --run-id rounded-fairness-new --repeats 2
```

The short controller always marks performance as indicative/excluded, uses one
second warm-up and three seconds measurement, and defaults to two repeats with
rotated edition order on both Java 21 and 25. Java 21 uses Mu4 platform workers;
Java 25 uses virtual workers. Every fresh JVM has a 512 MiB heap, explicit
1 GiB direct-memory ceiling, 1 GiB whole-server cap and no swap. The direct
ceiling reserves no memory. Runtime collector names, actual cgroup limits,
worker modes, class/source identities and cleanup are recorded. These are
low-density probes, not another optimized heap-budget comparison.

The native controller retains the frozen harness's original command plus the
actual `executed_generator_commands`; the latter includes the window override
and, for bursts, the saved timing script. Generator process groups and readers
use the maintained lifecycle guard. Successful trials require normal actual
JVM exit, full-body pre/post verification and native status-log reconciliation.

The fairness client has one foreground request outstanding, skips catch-up
bursts after delays, and records dispatch lateness separately. Its latencies
include Python-client overhead and are comparable only within that suite.
Neither client supplies a validated open-loop SLA. Native cases have about
96 measured responses per trial, so p99 is effectively the maximum; fairness
has about 600. One second of low-rate warm-up does not establish steady-state
JIT or GC behavior. Concurrent desktop use adds uncontrolled interference.
Small receive windows on loopback exercise frequent flow-control updates, not
realistic WAN latency. Short rates cannot establish maximum capacity.

`results.json` retains every attempt and failure, executed controller snapshots,
host snapshots, source identities and raw logs; `report.html` and `summary.json`
show observed metrics with zero eligible controlled-performance observations.
Run the focused harness checks with:

```bash
python -m unittest -v test_baseline test_connection_density test_latency_tails test_roundness
```

### Initial busy-host HTTP/2 observations

The paced window comparison found a repeatable direction across the two short
observations on each JDK. These are medians of observed per-trial p99 values,
not controlled estimates; each native trial has about 96 completed responses.
All three editions met the 32 responses/s target threshold.

| Java | Stream receive window | Mu3 p99 ms | Blocking p99 ms | NIO p99 ms |
| --- | ---: | ---: | ---: | ---: |
| 21 | 65,535 bytes | 7.823 | 5.002 | 4.544 |
| 21 | 1,023 bytes | 27.259 | 31.215 | 40.775 |
| 25 | 65,535 bytes | 4.498 | 5.709 | 5.613 |
| 25 | 1,023 bytes | 27.134 | 39.342 | 38.475 |

At the smaller window, observed server CPU per response was approximately
13.8 / 16.3 / 28.2 ms for Mu3 / blocking / NIO on Java 21, and
13.3 / 33.9 / 31.5 ms on Java 25. These include ongoing JVM warm-up under the
short protocol. The paired ordinary-window control does not show the same
clear Mu3 advantage. This identifies frequent flow-control updates as a useful
regression target; it does not establish the prevalence of 1 KiB windows among
real clients, attribute the gap to one implementation method, or predict WAN
performance.

All 24 same-connection fairness trials passed. Every paused stream resumed for
one correct byte, both same-connection and fresh-connection recovery worked,
and all trials met the 200/s target threshold. Observed paused p99 medians were
1.958 / 1.498 / 1.605 ms on Java 21 and 2.212 / 1.361 / 1.896 ms on Java 25
for Mu3 / blocking / NIO. This probe does not reveal a clear Mu3 latency
advantage. Mu3 had a smaller native-thread footprint on Java 21, while both
Mu4 versions used less sampled total memory in this low-density case. These
short measurements include startup/JIT effects and Python-client overhead.

Burst timings are relative to each connection, not a global barrier across
connections. In the eight-connection control, connection establishment can
shift batch phases and reduce actual overlap. The summary retains observed
peak-overlap ranges; compare these alongside upload latencies. A configured
stream/connection count must not be treated as proof of equal simultaneous
work. The one-connection burst cases directly exercise simultaneous streams.

The separate burst suite also passed all 48 trials and met the target threshold.
Every one-connection trial verified eight overlapping requests. Observed p99
medians illustrate why the paced result must not be generalized to all arrival
patterns:

| Java | Eight-stream burst workload | Mu3 p99 ms | Blocking p99 ms | NIO p99 ms |
| --- | --- | ---: | ---: | ---: |
| 21 | Download, 65,535-byte window | 24.279 | 17.121 | 19.369 |
| 21 | Download, 1,023-byte window | 61.821 | 44.450 | 54.689 |
| 21 | Raw upload, one connection | 49.059 | 19.729 | 25.399 |
| 25 | Download, 65,535-byte window | 22.341 | 17.744 | 30.511 |
| 25 | Download, 1,023-byte window | 59.011 | 57.767 | 50.735 |
| 25 | Raw upload, one connection | 33.737 | 32.201 | 29.188 |

Mu3 has no consistent burst small-window latency lead. Its two observed p99
values span approximately 47–77 ms on Java 21 and 52–66 ms on Java 25; NIO
spans 54–55 and 48–53 ms respectively. The paced small-window CPU advantage
also becomes much smaller with batching. A quiet, longer confirmation should
retain both arrival patterns rather than select whichever produces a desired
ranking. Burst uploads do not reveal a new Mu3 advantage on one connection.
The separate-connection upload control has observed peak overlap ranging from
four to eight, so it is not an exactly synchronized eight-way comparison.

Across the main suites, all 120 trials passed correctness/lifecycle checks and
met their offered-rate thresholds, with 23,606 measured responses. All 40
focused Python checks and 2,780 evidence-audit checks passed. The separate
six-trial Java 25 fairness pilot also passed and is not pooled into the main
summary. Every timing/CPU observation remains indicative, with zero eligible
controlled-performance rankings. No Java production code changed, and the
benchmark JVMs, generators and controllers were stopped after completion.

The combined report beside the checkout is
`nonblocking-validation/rounded-20261018-report.html`; it links all three
run reports, raw evidence, the earlier quiet baseline and memory comparison.
`rounded-20261018-summary.json` and `rounded-20261018-audit.json` retain the
combined metrics and validation result. Earlier quiet evidence of Mu3 strengths
in large bounded async HTTP/1 writes, HTTP/2 compression/uploads and dense TLS
connections remains relevant; these short additions do not replace it.

Further release coverage should include cold/resumed TLS connection churn,
realistic RTT/loss and limited bandwidth, WebSockets, varied/large headers,
TLS 1.2, and longer memory/GC runs. These remain unmeasured by this extension.
