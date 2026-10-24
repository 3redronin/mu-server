# Three-way NIO baseline

For the complete current rerun, use the
[full retest handoff](#full-retest-handoff-24-october-2026) below. The older
commands and results remain historical evidence; their default input revisions
must not be substituted for the newly frozen campaign.

Compare Mu3/Netty, pre-migration Mu4-blocking and optimized Mu4-NIO with one
application fixture, source/class/dependency inventories and a native h2load
generator. Production code and public async queue policy are outside this runner.

The [23 October deep-profile investigation](deep-profile-20261023.md) uses a
separate bounded async fixture to diagnose socket and HTTP/2 handoff costs,
compare TLS batching, and test fairness. Its selected change and three-way
results are recorded separately from the original workloads below.

The [HTTP/2 worker-handoff experiments](#october-23-http2-worker-handoffs)
follow that investigation, with separate dispatch counters, mixed-stream
fairness checks and matched-rate latency controls.

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

## October 18 bounded plaintext response handoffs

The first production optimization targets a single bounded asynchronous 1 MiB
HTTP/1 write over plaintext. HTTP/1 previously supplied approximately 16 KiB
render turns even when the transport could drain more. A transport-only 64 KiB
probe left throughput and write-family counts essentially unchanged; that
four-way pilot is preserved at
`nonblocking-validation/plaintext-drain-20261018/pilot/results.json`.

The combined change borrows up to 65,408 unchanged heap bytes after the first
ordinary HTTP/1 render turn and lets plaintext transport drain up to 64 KiB per
call. The owned output ring remains 8,201 bytes and the per-connection visit
budget remains 131,216 bytes. Larger borrows require a fixed-length, unencoded,
non-bodyless plaintext response and an application output buffer no larger than
16,352 bytes. This avoids merging a retained buffered prefix with a large borrow
and creating a temporary-file spill. TLS, chunked output, encoded output and
direct/read-only renderer input retain their existing rendering limits.

The matched experiment is beside the checkout at
`nonblocking-validation/plaintext-borrow-20261018/`. Its source snapshot,
class/dependency/fixture inventories and controller are preserved separately
from the original baseline. Only `ChannelConnection`, `Http1Response` and
`Http1ResponseOutput` production classes (including nested classes) differ from
the baseline. The NIO candidate production source digest is
`62fca35b5000d12f25eec40b4dfd840e48516d716b9073bd9289e3108cf66eee`.

Each short trial uses two seconds of warm-up and three seconds of measurement,
a fresh JVM, a 512 MiB heap and a 1 GiB total-memory cap without swap. Java 21
uses PLATFORM workers and Java 25 uses VIRTUAL workers for both Mu4 editions;
Mu3 retains its Netty policy. Two comparison repeats reverse paired edition
order. All observations are indicative and excluded from controlled performance
conclusions because the laptop was in use. These observations do not replace
the earlier quiet-host baseline.

Controls cover small responses, HTTPS bulk, HTTP/2 bulk, synchronous bulk and
chunked bulk. Distinct-connection isolation targets 2,000 small responses/s with
four foreground clients, alone and alongside 32 paused HTTP/1 readers. The
isolation fixture uses shared immutable 8 MiB payload arrays and one outstanding
async write per paused peer, exercising the changed borrow path without an
unbounded write queue. Paused peers must retain queued TCP bytes until
cancellation, and a fresh request must succeed afterwards. This is separate from
the earlier same-connection HTTP/2 stream-fairness experiment.

Observed medians for that bounded async plaintext workload (two repeats):

| Java | Mu3 responses/s | Mu4 blocking responses/s | NIO before responses/s | NIO after responses/s | NIO p99 before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| 21 | 15,613 | 16,710 | 4,201 | 10,262 | 3.835 → 1.654 ms |
| 25 | 16,692 | 17,204 | 4,668 | 10,687 | 2.565 → 1.310 ms |

NIO's observed throughput increased 2.44× / 2.29×, and CPU per response fell
from 1,061 / 1,011 µs to 440 / 437 µs on Java 21 / 25. It remains behind both
reference implementations in throughput. Mu3's Java 21 repeats varied from
13,951 to 17,274 responses/s, so the relative reference ranking is particularly
uncertain. NIO's paired ranges were 4,191–4,212 before and 10,165–10,358 after
on Java 21, and 4,622–4,714 before and 10,678–10,697 after on Java 25.

Whole-process write-family calls per response fell from approximately 127 to 35
on Java 21 and 100 to 29 on Java 25. These `/proc/PID/io` counts include selector
wakeups and control output; they are not socket-only syscall counts. Sampled
context switches for threads present at both measurement boundaries also fell.
This supports reduced handoff overhead, rather than merely a throughput change.

The owned connection buffers do not grow. Java 21's JMX buffer-pool observation
rose from 0.125 to 0.312 MiB, consistent with larger temporary direct buffers
cached per transport thread. The installed Java 25 JDK allocates those temporary
I/O buffers outside this JMX accounting; its zero counter must not be interpreted
as zero native memory. Installed JDK source excerpts are preserved in the
experiment's `jdk-native-buffer-sources.json`.

Total measurement cgroup peaks for NIO bulk had observed medians of
112 → 121 MiB on Java 21 and 91 → 97 MiB on Java 25. Short startup/JIT/GC-sensitive
runs cannot establish a retained-memory change from these differences. With
32 paused async readers, the corresponding medians were 202 → 195 MiB and
188 → 180 MiB. These observations do not establish a memory improvement either.

Control-workload throughput medians differed by approximately −2.2% to +5.2%.
They show no comparable large change, but these short runs cannot rule out small
regressions. All 16 fixed-rate isolation trials met their target, and all eight
paused-reader trials verified retention, queued output, cancellation and recovery.
Paused foreground p99 medians were 0.280 → 0.301 ms on Java 21 and
0.397 → 0.330 ms on Java 25. TLS and small-window HTTP/2 performance gaps remain
separate follow-up work.

Two separate Java 21 JFR diagnostic runs estimated 74,639 → 44,907 allocated
bytes per response from allocation-sample weights within the measurement
boundaries (approximately 40% lower). This is an estimate, not an exact
allocation counter; profiled throughput is not pooled into the comparison.
Remaining allocation samples point to response-header/buffered-prefix copies
and per-handoff objects. The recordings contain too few Java execution samples
to identify a reliable CPU hotspot. `profile-summary.json` links the recordings
and raw events. Header/prefix allocation and the remaining worker/transport
handoffs are candidates for the next bounded experiment.

The final regressions passed 2,069 tests on Java 21 and 2,114 on Java 25,
including TLS/HTTP/2 and WebSocket controls; 270 focused tests also passed on
each of Java 11 and 17. Added coverage checks large heap borrows, direct/read-only
fallbacks, custom buffered prefixes without file spills, source reuse only after
acknowledgement, rejected continuations, transport failure, and zero/partial
writes. A fresh Java 21 compilation reproduced all 603 frozen production class
files exactly.

All 78 final-candidate trials passed: four pilot trials, 72 paired/reference
comparison trials and two separate JFR diagnostic trials, with 5,002,484 measured
successful responses. The evidence audit passed 1,152 checks, including payload
probes, native response-log reconciliation, frozen build identity, paused-peer
retention/recovery, temporary-file cleanup, normal JVM exit and native-child
reaping. The initial transport-only pilot remains separate. All benchmark
processes were stopped after completion. `report.html`, `summary.json` and
`audit.json` in the experiment directory retain the ranges, resource observations,
exclusions, validation counts and links to individual trial evidence.

## October 18 retained-source and prefix-copy experiment

The next plaintext HTTP/1 candidate separates the size of a retained source view
from the amount of connection service allowed in one transport visit. Eligible
fixed-length, unencoded heap-source writes can retain up to 1 MiB after their
first ordinary render turn. Each plaintext socket write remains bounded by the
remaining 131,216-byte visit allowance, which is unchanged. The owned output
ring remains 8,201 bytes. Retaining a larger view does not allocate a body-sized
array: the public async operation already retains its source until completion.

HTTP/1 also keeps an owned header/buffered prefix separate from the unchanged
borrowed body. This removes the first body fragment's copy into mixed output.
Only the existing plaintext fixed-length eligibility permits this path. TLS,
content encoders, chunked output and larger custom application buffers keep
their previous capture policy. Direct/read-only renderer inputs retain bounded
scratch arrays and ordinary render sizes. Unexpected output after a borrow
falls back to owned storage; a failed spill preserves bytes already produced.

The transport continues to acknowledge actual consumed bytes, preserve unwritten
suffixes after zero/partial writes, and release borrowed storage only after
completion or cancellation. A 1 MiB retained source may span several visits;
that does not give its connection a 1 MiB service allowance. A shared-owner
socket test now explicitly stalls a large async write, serves a healthy client
on the same I/O thread, and verifies abort/completion cleanup.

Exploratory short probes isolated the changes before selecting the final bounds:

- Removing only the prefix/body copy left throughput near 10.3k responses/s,
  while separate JFR allocation-sample estimates fell from 47,658 to 29,243
  bytes per response.
- Retaining 256 KiB while preserving 64 KiB socket writes reduced observed
  CPU from 442 to 378 µs/response and surviving-thread context switches from
  24.9 to 13.1/response, with throughput still near 10.2k/s.
- Allowing one socket write to consume the remaining existing visit allowance
  increased the pilot from 10.5k to 12.0k responses/s.
- Retaining up to 1 MiB with that same service limit increased the final pilot
  from 10.4k to 12.5k responses/s, with 438 → 312 µs/response CPU.

These are separate single-pair busy-host probes, not repeated controlled
comparisons. The first three source snapshots and raw evidence remain in
`plaintext-prefix-20261018`, `plaintext-handoff-20261018` and
`plaintext-visit-20261018` beside the final `plaintext-span-20261018` evidence
root under `nonblocking-validation`. The final root's `sizing-probes.json`
links each pilot and records its source digest. They are not pooled into the
final comparison repeats.

The final candidate is compared with NIO commit `dbee7e68`, using the same
fixtures and preserved Mu3/blocking editions as the preceding experiment.
Its production source digest is
`5fcc2c0f1597c873b7429f84c9410127512270e6dd298cc435e0ebc0d3d42ed7`.
The matrix retains short two-second warm-ups and three-second measurements,
two repeats in reversed edition order, both Java 21/25, protocol/stream controls
and bounded async paused-reader isolation. Every observation remains indicative
while the laptop is in use; no controlled ranking is asserted.

The repeated comparison observed these median responses/second for bounded
async 1 MiB plaintext HTTP/1 responses over eight connections:

| Edition | Java 21 | Java 25 |
| --- | ---: | ---: |
| Mu3 | 18,497 | 18,777 |
| Mu4 blocking | 17,730 | 17,756 |
| Mu4 NIO before (`dbee7e68`) | 10,730 | 11,320 |
| Mu4 NIO candidate | 12,715 | 13,034 |

The candidate increased throughput by 18.5% on Java 21 and 15.1% on Java 25.
CPU per response fell from 429 to 309 µs and 424 to 310 µs respectively
(28.1% and 26.9% lower). Median p99 fell from 1.254 to 1.007 ms and 1.071
to 0.958 ms. Whole-process write-family calls fell from 35.0 to 13.0 and
29.2 to 12.8 per response; these counters include selector wakeups and control
output, so they are not socket-only syscall counts. NIO still trails both
reference editions in bulk throughput; its CPU cost is now closer to blocking.

Small-response, TLS, HTTP/2, synchronous and chunked control throughput moved
between -1.6% and +2.5%. These short runs cannot establish the absence of small
regressions. All 16 fixed-rate isolation trials met the 2,000 responses/second
foreground target. All eight paused-reader trials retained queued TCP output
for their 32 paused peers and passed cancellation/recovery checks. Foreground
p99 with paused peers was 0.334 → 0.314 ms on Java 21 and 0.343 → 0.332 ms
on Java 25. TLS and small-window HTTP/2 performance gaps remain separate work.

Two separate Java 21 JFR runs estimated 46,737 → 22,286 allocated bytes per
response from allocation-sample weights within measurement boundaries (52%
lower). These are estimates, not exact allocation counters, and their throughput
is excluded from the comparison medians. Larger socket writes increased Java 21
JMX buffer-pool usage from 0.312 to 0.563 MiB across four transport owners;
the output ring size per connection is unchanged. Java 25 reports zero for
these pools because its temporary I/O buffers are outside that accounting;
this does not mean zero native memory. Median measurement cgroup peaks rose
from 111.3 to 126.0 MiB on Java 21 and 97.4 to 104.8 MiB on Java 25.
Those process peaks include startup/JIT/GC effects and need longer controlled
memory measurements. The native buffer-cache increase alone does not explain
them, and the candidate is not demonstrated to be memory-neutral.

Final regressions passed 2,133 tests on each of Java 21 and 25, including
TLS/HTTP/2 and WebSocket controls, plus 287 focused tests on each of Java 11
and 17. New coverage exercises owned prefixes with large borrowed bodies,
source offsets and lifetime, spill failures, rejected continuations, partial
and zero writes, and healthy-client progress while an async peer stalls on the
same I/O owner. A fresh Java 21 compilation reproduced all 603 frozen
production class files exactly.

All 78 final-candidate trials passed (four pilot, 72 comparison and two JFR),
with 5,358,898 measured successful responses and 1,152 evidence audit checks.
The 14 earlier exploratory trials remain separate. All 92 benchmark JVM units
are inactive and all 92 native load-generator children have exited.
`plaintext-span-20261018/report.html`, `summary.json`, `audit.json`,
`profile-summary.json` and `final-process-check.json` preserve the observations,
qualification, validation and individual trial evidence.

## October 18–19 retained-output follow-ups

Five candidates were tested separately against frozen inputs, starting from
NIO commit `6b4afe8c`. Candidate diffs, source digests, decisions and individual
trials are linked from `nonblocking-validation/output-followups-20261018`.
The first-turn shortcut was discarded; the remaining candidates were retained
after the combined comparison:

| Candidate | Isolated observations and decision |
| --- | --- |
| Borrow the first HTTP/1 render turn at full size | Bulk throughput +0.7%, CPU -2.0%; insufficient evidence to justify another capture capability. Discarded. |
| Extend fixed-length HTTP/1 source borrowing to TLS | TLS bulk throughput about +55%, CPU/response about -51%; plaintext control flat. Retained. |
| Reuse the transport's independent drain view | Throughput flat. JFR samples estimated plaintext allocation 22,672 → 20,952 bytes/response and TLS allocation 108,103 → 105,013. Retained for lower buffer allocation. |
| Retain sixteen preferred HTTP/2 DATA fragments instead of four | Normal-window throughput +6.7%, CPU -8.2%; 1,023-byte-window throughput +1.4%, CPU -9.8%. Retained. |
| Avoid pending-write allocation on empty transport writes | Small/plaintext/TLS timings essentially flat. Simpler path explicitly avoids a pending-write object and its two buffer views. Retained. |

These are short, reversed-order Java 21 pilot pairs, not controlled performance
claims. The drain-view JFR samples are separate diagnostics, with estimated
rather than exact allocation counts. An isolated default-JIT ThreadMXBean
check measured 24 bytes per empty write after the change (the completion
future). Before the change, its three measurements ranged from 24–34 bytes
on Java 21 and 116–168 on Java 25. Java 21 already eliminated the extra
allocations after warm-up in that check; these isolated numbers do not measure
whole-server allocation or throughput. `empty-write-20261018/empty-allocation.json`
preserves the observations and method.

TLS now retains the same bounded source ranges already supported for plaintext
fixed-length HTTP/1, including separate ownership of a buffered prefix. The
1 MiB source view remains borrowed while its TLS records drain. This changes
renderer handoffs without changing SSLEngine progression, TLS buffer sizes,
record acknowledgements or transport service limits. A new regression keeps
the final ciphertext byte pending and verifies that a large source cannot be
acknowledged early, including failure at that boundary.

The reused transport view is reset before every attempt, including zero and
partial writes. It remains separate from the acknowledged source position and
the caller's buffer. HTTP/2's 261,632-byte retained range still yields between
individual DATA frames, obeys both flow-control windows, and retains ownership
until completion or cancellation. Tests cover round-robin fairness and
cancellation at the larger bound. Owned output rings and capture memory limits
remain unchanged. Empty writes still check failure and outstanding writes before
returning their completed future.

The combined matrix uses two reversed-order repeats on Java 21 and 25, with
two seconds of warm-up and three seconds of measurement per fresh JVM. The
Mu3/blocking reference builds and fixtures match the preceding experiments.
Both Mu4 editions use PLATFORM workers on Java 21 and VIRTUAL workers on
Java 25. All services have a 512 MiB heap, a 1 GiB cgroup limit and no swap,
with separate server and generator CPU sets. Bulk responses use one bounded
async 1 MiB heap-buffer write over eight connections. HTTP/2 and HTTPS
measurements use TLS 1.3 with `TLS_AES_128_GCM_SHA256`. Direct/read-only sources
retain their bounded scratch-copy path. Small-window HTTP/2 instead uses
one connection, eight streams and 1,023 bytes of stream credit. Each timing
retains its short-run and uncontrolled-host exclusions.

The final production source digest is
`338f15eafccd642392b832d2c11941bc3a2eefae35549f96efe27e90e8a4034a`.

The main comparison observed these median responses/second. Mu3 is pinned to
`78f02551`, blocking to `50ad1a70`, and NIO before to `6b4afe8c`:

| Java | Workload | Mu3 | Mu4 blocking | NIO before | NIO after |
| --- | --- | ---: | ---: | ---: | ---: |
| 21 | Plaintext HTTP/1 | 19,050 | 17,970 | 13,083 | 13,090 |
| 25 | Plaintext HTTP/1 | 18,689 | 18,373 | 13,391 | 13,396 |
| 21 | TLS HTTP/1 | 7,434 | 7,804 | 3,470 | 5,182 |
| 25 | TLS HTTP/1 | 6,960 | 8,090 | 3,715 | 5,638 |
| 21 | HTTP/2, normal window | 3,190 | 1,875 | 2,377 | 2,530 |
| 25 | HTTP/2, normal window | 2,715 | 2,426 | 2,611 | 2,681 |
| 21 | HTTP/2, 1,023-byte window | 271 | 240 | 208 | 211 |
| 25 | HTTP/2, 1,023-byte window | 269 | 205 | 199 | 208 |

TLS throughput increased 49.3%/51.8% on Java 21/25, with CPU per response
falling 1,300 → 692 µs and 1,287 → 628 µs (46.7%/51.2% lower). Its p99
fell 4.042 → 2.595 ms and 3.541 → 2.136 ms. TLS CPU cost is now close to
blocking's, but NIO throughput still trails both references. Normal-window
HTTP/2 throughput increased 6.4%/2.7%; small-window throughput increased
1.4%/4.6%, with CPU reductions of 9.6%/13.2%. Plaintext throughput stayed flat.
Java 25 normal-window HTTP/2 nearly matched Mu3's throughput in these short
runs, while still using substantially more CPU per response (2,198 versus
1,497 µs). This does not establish equivalence across HTTP/2 workloads.

Small shifts in the short controls prompted twelve additional trials with
five-second warm-ups and twelve-second measurements, still using two reversed
edition orders. Java 21 chunked throughput was 2,923 → 2,893 responses/second
(-1.0%), with p99 4.172 → 4.170 ms. Java 25 synchronous bulk throughput was
3,269 → 3,242 (-0.8%), with p99 3.122 → 3.183 ms. Java 25 small-response
throughput was 216,648 → 220,074 (+1.6%), with p99 0.542 → 0.529 ms.
These small differences remain uncertain; the longer checks did not reproduce
the larger short-run tail shift consistently. Their medians are kept separate
from the main comparison.

The longer small-response trials exceed the native JSON latency vector's
one-million-sample cap. Confirmation latencies therefore use the complete
reconciled status-200 request logs. One completed trial was excluded after the
old tail helper rejected its capped vector; its evidence is preserved in
`confirmation/interruption.json`, and the trial was rerun with the corrected
post-processing. Server and generator exits were successful.

Separate Java 21 JFR samples estimated allocation per response of 21,890 →
21,041 bytes for plaintext, 165,537 → 107,688 for TLS (35% lower), and
227,761 → 216,204 for normal-window HTTP/2. Sample weights are estimates,
and profiled throughput is not pooled with ordinary trials. Short process
cgroup peaks decreased for plaintext/TLS, but increased by roughly 1–8 MiB
for HTTP/2. These peaks include startup/JIT/GC effects and do not establish
retained-memory neutrality. JMX buffer-pool accounting still excludes some
native memory, particularly Java 25 temporary I/O buffers; full resource
observations are retained in the report.

All 40 fixed-rate trials met the foreground target; all 20 paused-reader
trials retained queued output and passed cancellation/recovery checks. The
combined changes passed 2,146 regression tests on each of Java 21 and 25,
and 295 focused tests on each of Java 11 and 17. Fresh compilation reproduced
all 603 frozen production class files exactly.

All 146 final-candidate trials passed (eight isolation pilot, 120 main
comparison, six JFR and twelve longer control trials): 16,476,315 measured
successful responses and 2,317 evidence audit checks. The 48 exploratory
trials and the one excluded post-processing trial remain separate. All 195
benchmark JVM units are inactive and their load generators have stopped.
`output-followups-20261018/report.html` links the comparisons, allocation
diagnostics, candidate decisions, audits and raw evidence.

## October 20: eight isolated optimization experiments

None of these production candidates was retained. They either failed to show a
worthwhile benefit or exchanged throughput for higher CPU, memory or latency.
Production source and all 603 compiled classes were restored to `e78fe2e8`;
these results do not close the gaps in the preceding Mu3 / blocking / NIO
comparison. Mu3 and preserved Mu4 blocking were not rerun in this batch.

Each experiment started independently from that frozen NIO baseline, passed
focused Java 21 tests, and used two reversed-order comparison pairs. Pilots used
two seconds of warm-up and three seconds of measurement. The machine was not
controlled, so small changes remain uncertain. Larger plaintext-write controls
also used three seconds of warm-up and five seconds of measurement on Java 21
and 25. These are indicative measurements, not general rankings.

The bulk fixture submits one bounded async 1 MiB heap-buffer write per response.
Normal workloads use eight connections. The small-window HTTP/2 case uses one
connection, eight streams and 1,023 bytes of stream credit. HTTPS and HTTP/2 use
TLS 1.3 with `TLS_AES_128_GCM_SHA256`. Each JVM has a 512 MiB heap, a 1 GiB
cgroup limit and no swap, with separate server/generator CPU sets. Mu4 uses
PLATFORM workers on Java 21 and VIRTUAL workers on Java 25.

| Experiment | Observed pilot result on Java 21 | Decision |
| --- | --- | --- |
| Reuse SSLEngine wrap/unwrap buffer arrays | HTTPS +0.4%, HTTP/2 -0.7%; CPU did not improve | Discard |
| Direct ciphertext output buffer | HTTPS -29.3%, HTTP/2 -18.5%, worse latency | Discard |
| Remove the output pending-byte check before locking to drain | Plaintext +0.2%, HTTPS -0.5%, small-window HTTP/2 -0.5%; CPU flat | Discard |
| Wake selectors only around selection | Plaintext write-family calls -22%, but throughput -1.3%; HTTPS throughput -4.2%, CPU +7.1% | Discard |
| Eight transport owners instead of four | Plaintext +10.5%, HTTPS +19.9%, but CPU +20–27%; normal-window HTTP/2 -8.8% | Keep four-owner default |
| Raise ready-frame HTTP/2 batching threshold from 8 KiB to 16 KiB | Normal-window +0.1%, small-window -0.2%; small-window CPU +3.4% | Discard |
| Mutable per-stream HTTP/2 credit cells | Normal-window +1.3%, small-window +0.6%; CPU reductions near 1% | Insufficient benefit demonstrated; discard |
| Double plaintext visit/write allowance to 262,432 bytes | Plaintext +4.7%, CPU -2.8%, write-family calls -31%; about 0.5 MiB more Java 21 buffer-pool storage with four owners | Discard after fairness check |

The two-owner follow-up also found a tradeoff: HTTPS throughput fell 44.7%,
while normal-window HTTP/2 throughput fell 18.4% and CPU per response fell
25.4%. Thread count can change where work is limited; these results do not
support a new default.

The larger plaintext allowance looked promising in saturated bulk comparisons.
Longer pairs reproduced throughput increases of 3.9% on Java 21 and 5.1% on
Java 25. However, it increased small-request latency when those requests shared
a transport owner with continuously draining bulk downloads. Eight native
clients each keep one bounded 1 MiB download outstanding, while a Python
foreground client sends 128-byte requests at 1,000 requests/second. The test
checks every foreground body and records dispatch lateness and latency from
the intended send time as well as actual-send latency.

Two reversed-order pairs, with two seconds warm-up and five measured seconds,
observed these medians on one shared owner:

| Java | Existing allowance p99 | Larger allowance p99 | Change | Foreground rate |
| --- | ---: | ---: | ---: | ---: |
| 21 | 0.762 ms | 1.040 ms | +36.5% | 1,000/s in both |
| 25 | 0.718 ms | 1.028 ms | +43.2% | 1,000/s in both |

Background downloads continued completing throughout measurement. A fresh pair
per JDK using the reusable benchmark reproduced the latency increase. These
checks remain separate from the saturated-throughput medians. The regression
is evidence against this allowance change; it does not establish a universal
latency ratio for other loads or owner counts.

The new [busy-writer fairness benchmark](busy_writer_fairness.py) complements
paused-reader isolation. It compares two frozen NIO fixtures with identical
bounded-async application sources, verifies their manifests, checks the
connection cohort at both measurement boundaries, and requires bulk completions
in every complete measurement second. It preserves raw latency samples,
background request logs, source identities, whole-process resource observations
and cleanup results. It also reports scheduled latency so a delayed foreground
client cannot silently hide queueing.

For example, from the repository root, using the sibling validation environment:

```sh
../mu-conformance/.venv/bin/python benchmarks/nio-baseline/busy_writer_fairness.py \
  --before ../nonblocking-validation/output-followups-20261018/after-async \
  --after ../nonblocking-validation/ex08-network-clock-20261020/after-async \
  --out ../nonblocking-validation/busy-writer-recheck \
  --jdks 21,25 --repeats 2
```

Use a new output directory. `--nio-threads`, `--background-clients`,
`--foreground-rps`, `--warmup`, `--duration`, `--server-cpus` and
`--generator-cpus` permit matched controls on other machines. The historical
`ex08-network-clock` directory name predates the final experiment selection;
its frozen source and patch change only the plaintext allowance.

All eight candidates passed their targeted Java 21 tests: respectively 106,
106, 156, 109, 49, 788, 788 and 156 cases. The reusable benchmark passed real
socket runs on Java 21/25, and an injected failure after starting its generator
verified termination of that owned process, normal JVM shutdown and evidence
capture. No full application suite was rerun after restoring production code;
a fresh Java 21 compilation exactly reproduced the baseline source digest
`338f15eafccd642392b832d2c11941bc3a2eefae35549f96efe27e90e8a4034a`
and all 603 production classes.

The audit passed 2,275 checks over 128 native performance trials, twelve
fairness trials and one deliberate cleanup failure. Native measurements
reconciled 10,322,383 successful responses; the fairness probes additionally
verified 60,000 foreground responses. All 141 owned JVM units are inactive,
and their native generators have been reaped. The deliberate failure is
excluded from performance results.

[The portable summary](experiments-20261020.json) includes medians, ranges,
source digests, decisions and validation. The complete local report is
`nonblocking-validation/eight-experiments-20261020/report.html` beside the
checkout, with links to candidate patches, frozen manifests, raw results,
regression logs and the audit. Whole-process write-family counters include
selector/control writes; short cgroup peaks include startup and GC effects.
JMX buffer pools omit some native memory, particularly on Java 25, and are not
a substitute for whole-process memory accounting.

The next substantial hypothesis is reducing transport/HTTP/2 writer handoffs
while preserving frame-level flow control, cancellation and fairness. These
experiments do not prove that such a redesign will help, but they provide
reasons to prefer investigating it over adopting these tuning tradeoffs.

## October 23 HTTP/2 worker handoffs

This investigation starts from `fdeb0518`, including its bounded TLS output
coalescing. It tests whether fewer HTTP/2 writer dispatches can reduce CPU use
without increasing response buffers, delaying cancellation or sacrificing
small-stream latency. The [portable measurements](worker-handoffs-20261023.json)
include every completed trial, source identities and ranges. These are local
loopback indications, not a new release baseline: foreground CPU activity
increased during the final three-server matrix, and all implementations slowed.

The bulk fixture performs one bounded 1 MiB async write and completes the
response. It uses eight connections, one stream each, four NIO owners, a
65,535-byte stream window and a 16,777,215-byte connection window. TLS is 1.3
with AES-128-GCM. Servers have a 512 MiB heap and 1 GiB cgroup limit with no
swap; server and generator use separate logical CPU sets. Mu4 uses platform
workers on Java 21 and virtual workers on Java 25; mu3 retains its defaults.
The mu3 and blocking references are
frozen earlier builds, identified in the JSON; blocking is not rebuilt from
the candidate's source.

Four variants continued a non-final DATA fragment on the transport owner after
its previous fragment drained. They retained exclusive writer ownership,
per-frame flow-control reservations, the source borrow and the normal final
completion on a worker. Successive variants also yielded for unread input,
yielded to the next selector turn, and disallowed continuation whenever another
stream remained open.

| Owner-continuation variant | Bulk throughput, Java 21 / 25 | CPU per response, Java 21 / 25 | Same-connection small-response p99 |
| --- | --- | --- | --- |
| Initial bounded continuation | -3.7% / -0.2% | -10.7% / -13.0% | Worse on both JDKs |
| Also yield for input/read work | Not measured | Not measured | Worse on both JDKs |
| Also yield between selector visits | +9.4% / +9.1% | -21.2% / -21.9% | About 18% / 16% worse |
| Also require exactly one open stream | +7.3% / +9.1% | -20.5% / -21.9% | About 23% / 20% worse |

Each percentage compares the median of two trials with its own alternating
baseline. The fairness workload keeps a 1 MiB response continuously active
while requesting 128-byte responses at 500/s on the same connection, with one
NIO owner and a 2 MiB stream window. It hashes every completed body and reports both actual-send and
scheduled latency. Its Python client is a contention probe, not a maximum-rate
generator. Absolute timings differ between phases; compare within a phase.
All four owner-continuation variants were discarded. The latency tradeoff
persisted despite the extra guards. These measurements do not establish whether
the remaining delay is in the server scheduler, socket queues or the client.

The fifth variant handles a bounded prefix of valid WINDOW_UPDATE commands on
the reader's existing worker while output is awaiting transport drainage. It
acquires the writer's existing ownership guard. Overflow, closed-stream
lifecycle work, SETTINGS, resets and all other commands stay in order for the
ordinary writer; they do not wait for the socket to drain. Encoding, transport
completion and application callbacks retain their previous execution paths.

Separate instrumented runs measured writer submissions per completed response:

| Diagnostic | Java 21 | Java 25 |
| --- | ---: | ---: |
| Baseline | 102 | 113 |
| Owner continuation, final variant | 52 | 53 |
| Reader applies valid credit | 88 | 92 |

Counters include warm-up and probes; instrumented timings are excluded from
speed comparisons. In the initial uninstrumented credit trials, CPU per bulk
response fell about 5%. Longer same-connection fairness trials had overlapping
p99 ranges: Java 21 baseline 2.280–2.600 ms versus candidate 2.280–2.359 ms;
Java 25 baseline 2.259–2.280 ms versus candidate 2.251–2.461 ms. This does not
establish a latency improvement. Tiny responses, eight streams sharing one
connection, and very small flow windows showed no consistent benefit.

A matched-rate control offered 1,000 bulk requests/s. Its first two Java 21
pairs saved CPU but raised p99 from 2.410–2.495 ms to 2.681–2.742 ms. That
prompted four further alternating pairs before selection. A sixth experiment
coalesces only notifications for consecutive WINDOW_UPDATE frames, retaining
all validation on the writer. It flushes the notification before other frame
types, before error handling and at the end of each bounded reader turn.

The four-pair Java 21 recheck did not reproduce the earlier p99 penalty:
baseline p99 ranged from 2.795 to 3.302 ms and candidate p99 from 2.672 to
2.919 ms, lower in three pairs and 1.5% higher in one. Median CPU cost was
1,834 versus 1,817 microseconds per response, only a 0.9% reduction in this
recheck. Both the initial adverse latency result and this mixed confirmation
remain in the evidence. A fixed percentage speed or latency improvement is
not established.

The notification-only alternative was discarded. Its instrumented dispatches
per response were 102.38 versus 102.05 on Java 21 and 113.45 versus 113.65 on
Java 25. Uninstrumented median CPU changed by -0.1% / -0.5%, with overlapping
ranges. The additional notification state and flush paths were not justified.

The smaller-window fairness control used 65,535 bytes of stream credit so the
reader encountered frequent window updates. The credit shortcut increased
actual-send p99 in all four pairs: medians 0.534 to 0.563 ms on Java 21 and
0.477 to 0.486 ms on Java 25, about 5.5% and 1.9%. Scheduled p99 also increased.
These are small absolute differences, but they provide no evidence for keeping
the extra credit-processing path without a latency tradeoff.

A seventh variant skipped the shortcut whenever more frames were already
buffered, prioritizing the reader's existing work. Its fresh paired control
had flat actual-send p99 on Java 21 and a 2.1% increase on Java 25; scheduled
p99 increased 1.9% / 5.2%. Bulk progress also fell 2.7% / 0.3%. This did not
establish a clear win, so no further saturated throughput tests were run for
that variant.

**All seven experimental production changes were discarded.** The final
production source is identical to `fdeb0518`, with SHA-256
`8785d21fb584c43b2eda111b38ba8fe89151ec0da840fe8c37e3a6dbb9487704`.
The retained changes are four regression cases and the configurable stream
window in the fairness runner. The experiments show that substantial handoff
savings are possible, but these implementations do not meet the combined CPU
and latency goal. They do not prove that a future design cannot do so.

The credit candidate passed `mvn clean verify` on Java 11, 17, 21 and 25, with
NullAway enabled on Java 21. Each suite discovered 5,535 tests, with no failures
or errors; Java 11/17 skipped the 176 virtual-thread-only cases. Added coverage
checks command ordering, bounded credit processing, overflow, negative windows,
retired streams and cancellation while transport still owns a response buffer.
The notification alternative passed 135 focused cases, including credit
arriving at the end of input and at the reader's 64-frame turn boundary.
The buffered-frame guard also passed 135 focused cases. These candidate
checks are separate from validation of the final restored production code.

The final checkout passed 235 focused tests on each of Java 11, 17, 21 and 25,
with no failures or skips and NullAway enabled on Java 21. The four retained
cases check that credit at end-of-input resumes a blocked writer, including
the 64-frame reader boundary, and that resets are processed while transport
is stalled without releasing a borrowed response prematurely.

The evidence audit passed 1,882 checks across 120 native and 68 fairness trials.
Native records reconcile 6,493,758 measured successful responses. The fairness
probes additionally hashed 440,000 measured foreground responses and 355,233
completed bulk responses, including bulk warm-up. All 188 benchmark-owned JVM
units are inactive and native generators were reaped. The audit also verifies
the frozen candidate used by the full test matrix and the restored production
digest and final test sources.

The complete local evidence is beside the checkout in
`nonblocking-validation/h2-handoffs-20261023/report.html`. It links frozen
sources, patches, raw request timestamps, every comparison, test logs and the
cleanup audit. The interrupted `bulk-credit` controller's final trial was
already saved and complete; its recovered eight-trial ledger is kept beside
the original incomplete aggregate. No failed launch is counted as a performance
result. Native load uses body-hash probes before and after measurement; it
does not hash every loaded response. Memory observations include whole-process
RSS and cgroup usage, not just heap.

To repeat a matched-rate comparison on this host with the frozen inputs:

```sh
../mu-conformance/.venv/bin/python \
  ../nonblocking-validation/h2-handoffs-20261023/run.py \
  --label credit-rate-repeat \
  --before ../nonblocking-validation/deep-profile-20261023/release-selected \
  --after ../nonblocking-validation/h2-handoffs-20261023/candidate-credit \
  --roles nio --cases h2bulk --clients 8 --owners 4 --rate 1000 \
  --warmup 10 --duration 20 --repeats 4 --jdks 21
```

Use a fresh label. For same-connection fairness, use
`benchmarks/nio-baseline/h2_writer_fairness.py` with the corresponding
`after-async` directories, `--warmup 5 --duration 20 --repeats 3 --jdks 21,25`
and a fresh `--out` directory. This keeps diagnostic counts, saturated bulk
throughput, matched-rate latency and mixed-stream fairness separate.
The fairness runner also accepts `--stream-window 65535` to exercise frequent
WINDOW_UPDATE traffic instead of its default 2 MiB stream window.

## Handlers waiting for a downstream service

`downstream_wait.py` compares mu3, mu4-blocking and mu4-NIO while handlers wait
for a separate loopback HTTP service. It measures both application styles:
a handler that waits on the outbound future and an async handler that returns
while the same outbound operation is pending. Both use one shared JDK HTTP
client with eight named executor threads, HTTP/1 keepalive, a one-second
connect timeout and a two-second whole-response deadline, including the body.
The request timeout is also configured, but the fixture additionally times
out a dependent future and cancels the original exchange when necessary.
Both return the identical
128-byte body through one bounded `AsyncHandle.write`. Holding the client and
response API constant isolates the cost of retaining the handler during the
wait; it is not a comparison of every synchronous and asynchronous client API.

The dependency runs in a separate Python asyncio process, with timer-based
delays and no artificial connection-pool limit. There is a direct-response
control, a zero-delay downstream control, and 250 ms downstream waits. A 50 ms
delay is available with `--delays 0,50,250`. An independent `/local` endpoint
continues receiving fast requests while downstream calls are pending.

Default loads are 400 background and 100 foreground requests/s. Background
capacity is 256 HTTP/1 connections or eight TLS HTTP/2 connections with 32
streams each. The foreground has its own connection using the same ingress
protocol. Both generators pace against a global schedule and skip overdue
slots instead of creating catch-up bursts. Exhausting background capacity
also records a missed slot rather than queuing unbounded work. Every response
body is validated. Raw background samples record dispatch lateness, failures,
and warm-up/measurement phase; foreground samples contain both actual-send
and scheduled latency, plus missed slots. This bounded workload is not an
open-loop latency guarantee under overload.

The initial matrix has 48 short trials: HTTP/1 direct, zero-delay blocking
and async, and 250 ms blocking and async; HTTP/2 direct and 250 ms blocking
and async; all three editions on Java 21 and 25. Each trial has three seconds
of warm-up and five measured foreground seconds. Background traffic continues
for one additional second to cover control/probe overhead. Reported background
throughput counts completions in the exact foreground measurement window;
raw samples retain the complete run. One repeat rotates edition order across
cases. These runs validate the benchmark and provide indicative observations,
not release rankings or a full architectural baseline.

Mu4 uses PLATFORM handlers on Java 21 and VIRTUAL handlers on Java 25. Mu3
uses its defaults. `/mode` verifies the actual worker and transport policy.
Application waiting counters are separate from native thread counts, which
also include transport, JDK HTTP client, GC and other JVM threads. CPU per
successful response includes the outbound client. CPU snapshots bracket the
foreground interval and include the small control overhead. RSS and cgroup
memory are whole-process observations, including off-heap usage; their peaks
and native thread peaks are sampled during measurement, not just heap totals.

The server has a 512 MiB heap and a 1 GiB cgroup cap with no swap. Default CPU
sets on this host are server `0-3,8-11`, generators `4-6,12-14`, and downstream
`7,15`; these reserve separate physical cores and their SMT siblings. The
runner saves CPU topology and verifies non-overlapping logical CPU sets;
check the topology before using these defaults on another host. A direct
downstream calibration offers twice the background rate. Missed offered load,
generator CPU saturation and downstream timer lateness remain explicit report
qualifications. The downstream service retains actual delay, timer lateness,
active counts, completions and disconnects separately from server statistics.

The fixture cancels outstanding outbound futures on response completion and
shutdown. Front-end cancellation is best effort: the server may not deliver a
completion notification while an application handler is blocked, and an
HTTP/1 socket close is not necessarily a prompt cancellation signal. Validation
records whether cancellation reached the outbound future or its finite request
deadline settled the work. It also exercises downstream status/body errors,
connection refusal, disconnects, timeouts before headers and after headers
with a stalled body, timeout-to-504 mapping, recovery, overlapping
requests, foreground progress and shutdown with pending operations. Injected
startup and measurement failures verify that owned children are reaped; forced
cleanup cannot count as a successful performance trial.

Prepare new immutable inputs, validate, and run the short matrix on this host:

```sh
PYTHON=/home/daniel/Work/3redronin/mu-server/mu4/mu-conformance/.venv/bin/python
INPUTS=../nonblocking-validation/downstream-wait-repeat/inputs

"$PYTHON" benchmarks/nio-baseline/downstream_wait.py prepare \
  --inputs ../nonblocking-validation/deep-profile-20261023/release-selected/after-async \
  --out "$INPUTS"
"$PYTHON" benchmarks/nio-baseline/downstream_wait.py validate \
  --inputs "$INPUTS" --out ../nonblocking-validation/downstream-wait-repeat/validation
"$PYTHON" benchmarks/nio-baseline/downstream_wait.py run \
  --inputs "$INPUTS" --out ../nonblocking-validation/downstream-wait-repeat/comparison
"$PYTHON" benchmarks/nio-baseline/audit_downstream_wait.py \
  --inputs "$INPUTS" \
  --comparison ../nonblocking-validation/downstream-wait-repeat/comparison \
  --validation ../nonblocking-validation/downstream-wait-repeat/validation \
  --out ../nonblocking-validation/downstream-wait-repeat/audit.json
```

Every output directory must be new. `prepare` verifies the parent manifest,
copies its compiled production classes, and compiles the same fixture source
unchanged against each edition with `--release 11`. It preserves the frozen
mu3/blocking references and records the NIO source identity. These editions
have different production revisions, so differences cannot be attributed
solely to their transports. The runner snapshots its sources and Python package
versions, records all launch commands, verifies manifests before and after the
run, and checks actual JVM exit before resetting its owned systemd units.
Earlier evidence is never rewritten. `report.html`, `summary.json`, raw samples,
per-trial resource records, downstream observations and launch/cleanup evidence
are saved beneath the chosen output directory.

Run the focused harness tests with the same virtual environment:

```sh
"$PYTHON" -m unittest discover -s benchmarks/nio-baseline -p 'test_*.py'
```

### Initial downstream observations, 2026-10-24

All 48 short trials passed, with no response/body failures and offered rates
within the runner's 2% tolerance. Background throughput was 398.2–400.2/s;
foreground throughput was 99.6–100.0/s. The generators recorded 51 missed
background slots and eight missed foreground slots during the foreground
measurement windows; these were not hidden by catch-up requests. Direct
downstream calibration achieved 799.8/s at both zero and 250 ms delay, with
timer-lateness p99 of 0.052 and 1.088 ms. Maximum background generator CPU
use across the comparison was 0.328 cores, leaving substantial generator
headroom for this offered load.

The 250 ms cases held about 100 downstream operations in flight. Sampled peak
native thread counts, including the outbound client and JVM threads, were:

| Java | Ingress | Handler style | mu3 | mu4-blocking | mu4-NIO |
| --- | --- | --- | ---: | ---: | ---: |
| 21 | HTTP/1 | Blocking | 158 | 410 | 165 |
| 21 | HTTP/1 | Async | 53 | 309 | 68 |
| 21 | HTTP/2 TLS | Blocking | 146 | 173 | 173 |
| 21 | HTTP/2 TLS | Async | 48 | 66 | 81 |
| 25 | HTTP/1 | Blocking | 151 | 43 | 43 |
| 25 | HTTP/1 | Async | 54 | 41 | 42 |
| 25 | HTTP/2 TLS | Blocking | 145 | 41 | 50 |
| 25 | HTTP/2 TLS | Async | 49 | 40 | 51 |

These observations separate two costs. Async handlers remove the waiting
application-worker population. On Java 21, mu4-blocking also retains platform
threads for its HTTP/1 connections. Java 25 virtual threads greatly reduce
both costs in native-thread terms. NIO still consumes application workers when
the application chooses to block. Mu3's async handlers used fewer native threads
than either Mu4 edition in both Java 21 protocols in this run.

Whole-process RSS in the 250 ms cases ranged from 219–308 MiB for mu3,
139–200 MiB for mu4-blocking, and 153–212 MiB for mu4-NIO. These are sampled,
short-run observations, not steady-state memory requirements. Foreground
actual-send p99 across the complete matrix ranged from 0.354 to 4.803 ms;
scheduled p99 reached 8.648 ms. The short warm-up, single repeat and potentially
busy host make CPU and latency rankings tentative. All three editions sustained
this modest offered load; this is not a saturation or maximum-capacity result.

The complete fault matrix passed all 24 edition/JDK/protocol/handler-style
combinations, including a downstream that sends headers and then stalls its
body. Both injected lifecycle failures were rejected as performance results
and their children were reaped. The harness suite passed 60 tests, including
20 new tests. An independent audit passed 1,458 checks, reconciling 95,943
background and 23,992 foreground measured responses. All 74 owned JVM units
were inactive and all 127 owned generator/dependency children were reaped.

The portable results are in
[`downstream-wait-20261024.json`](downstream-wait-20261024.json). The full local
report is `../nonblocking-validation/downstream-wait-20261024/comparison/report.html`,
with validation in `validation-deadline`, prepared inputs in `inputs-deadline`,
and the independent audit in the campaign's `audit.json`. Earlier harness
pilots remain separate from this completed comparison. The frozen references
are mu3 `78f02551` and mu4-blocking `50ad1a70`; NIO production matches
`fdeb0518`, with SHA-256
`8785d21fb584c43b2eda111b38ba8fe89151ec0da840fe8c37e3a6dbb9487704`.
This addition changes benchmark fixtures, runners, tests and evidence only.

## Full retest handoff, 24 October 2026

`full_retest.py` prepares, validates, checkpoints, resumes and audits the whole
collection. The campaign prepared on this workstation is:

```text
/home/daniel/Work/3redronin/mu-server/mu4/nonblocking-validation/full-retest-20261024
```

The long baseline has **not** been started. Its comparisons use frozen mu3
`78f02551`, mu4-blocking `50ad1a70`, and current NIO production matching
`fdeb0518` (source SHA-256
`8785d21fb584c43b2eda111b38ba8fe89151ec0da840fe8c37e3a6dbb9487704`).
These are different production revisions, so results compare complete editions;
they cannot isolate transport implementation as the only cause of differences.

Preparation is complete: 75 current harness unit tests passed, the selected
102 smoke trials passed their independent audit, all 24 downstream fault
combinations passed, and both injected lifecycle failures were rejected and
cleaned up. Pause/resume preserved 21 original trial hashes. A separate
3 s warm-up / 10 s measurement burst check passed for all three editions,
verifying operation beyond the former eight-second script. All 131 JVM units
from the main validation are inactive, with no owned processes remaining.
The retained mu3 failure described below is included in the
[portable readiness record](full-retest-readiness-20261024.json); it is not
counted as a successful trial.

### What the runner will execute

There are **104 workloads, 560 comparison groups and 1,680 fresh JVM trials**.
Every group runs all three editions consecutively. Main comparisons use 20 s
warm-up, 60 s measurement and three repeats, rotating edition order so each
edition occupies each position once. Both Java 21 and Java 25 are tested.
Mu4 uses PLATFORM on 21 and VIRTUAL on 25; mu3 retains its defaults. The actual
worker/transport policy is checked through `/mode`.

| Family | Cases | Coverage |
| --- | ---: | --- |
| Core | 6 | Sync small, fixed/chunked HTTP/1 bulk, HTTPS bulk, H2 bulk and multiplexed small responses |
| Isolation | 6 | HTTP/1, HTTPS and H2 foreground, alone and with 128 paused downloads |
| Async | 10 | Bulk, multiplexing, two compression entropies, raw/multipart uploads, 256 connections |
| Response sizes | 7 | TLS 16/64/128/256 KiB and small responses on eight HTTP/1, HTTPS or H2 connections |
| H2 windows | 3 | Small and large receive windows; one multiplexed connection and eight separate connections |
| Matched load | 3 | TLS 1 MiB at 4,000/s, TLS 64 KiB at 25,000/s, H2 1 MiB at 1,000/s |
| Bounded output | 4 | Small fixed-rate and bulk TLS, alone and with 32 paused downloads |
| Streaming | 5 | HTTPS and H2 controls, 128 paused peers, and 768 paused H2 peers |
| Rounded H2 probes | 10 | Multiplexed download/upload, bursts, and shared-connection paused-stream fairness |
| Density | 6 | HTTP/1 and HTTPS at 0/1,024/4,096 idle connections, 512 MiB total cap |
| Heap tuning | 16 | 512/1,024 MiB caps, four heap sizes, zero and 4,096 idle TLS connections |
| Heap confirmation | 4 | Independent confirmation of each selected heap, including zero-idle controls |
| Busy writers | 8 | HTTP/1 and HTTPS with/without bulk writers; H2 shared-connection bulk with two windows |
| Collectors | 4 | Explicit G1 small/HTTP1 bulk/H2 bulk, plus Serial small-response control |
| Downstream waits | 12 | Direct controls and blocking/async handlers; HTTP/1 and H2; 0/50/250 ms delays |

`plan.json` is the authoritative list of exact parameters and execution order.
Heap tuning runs first, once per candidate, with 5 s warm-up and 10 s
measurement. It chooses the smallest heap that sustains the fixed target with
a complete cohort and a successful zero-idle control. If none qualifies, the
recorded fallback chooses the best correctly completed rate, or the largest
established cohort. `heap-selection.json` marks that fallback explicitly.
Confirmation then uses fresh JVMs and the full three-repeat protocol. Never
choose a different heap by looking at confirmation results.

Allow **about 36 hours of timed work plus setup, verification and reporting**,
spread across quiet sessions. Connection establishment and resource-limited
cases can add substantial time. Do not shrink durations or omit difficult
cases to finish in one night. Discarded optimization candidates, unbounded async
writes and the old 1,024-paused-request admission-limit diagnostic are outside
this baseline. No profiler is attached during measurements.

Most trials have a 512 MiB heap, 1 GiB total cgroup cap and no swap. Density
and heap experiments specify their own equal total caps. Compare whole-process
RSS/cgroup observations, not heap alone: mu3 uses off-heap Netty memory.
The server CPU set is `0-3,8-11`; native clients use `4-7,12-15`. Downstream
tests use `4-6,12-14` for clients and `7,15` for the dependency. These sets match
this workstation's physical cores and SMT siblings. The dependency is calibrated
at twice the requested background rate in each session that runs those cases.

### Instructions for the executing agent

Use the existing prepared campaign. Do not rebuild, switch inputs, update its
copied code or packages, change JDKs, or run the older controllers alongside it.
Preparation froze source identities, compiled classes, dependencies, fixtures,
controller code and runtime versions. Integrity checks run on resume and around
measurements. The wrapper serializes its own campaigns and records owned
processes; it does not prevent unrelated desktop activity or other benchmarks.

Set these variables in the repository working directory:

```sh
PYTHON=/home/daniel/Work/3redronin/mu-server/mu4/mu-conformance/.venv/bin/python
CAMPAIGN=/home/daniel/Work/3redronin/mu-server/mu4/nonblocking-validation/full-retest-20261024
RUNNER="$CAMPAIGN/code/full_retest.py"
AUDITOR="$CAMPAIGN/audit-code/full_retest.py"
"$PYTHON" "$RUNNER" status --out "$CAMPAIGN"
```

Check that `validation.json` says `complete: true`. Its validation uses 102 short
trials across all three editions and both JDKs, the Python harness tests and
24 downstream fault/recovery combinations plus two injected lifecycle failures.
Smoke cohorts are capped at 64 idle connections and eight paused peers; these
checks establish runner readiness, not full-scale performance or capacity.
Preparation also exposed an intermittent **mu3 Java 25 H2 upload shutdown
failure**: all 4,443 measured responses succeeded, but after `STOPPED` a
non-daemon handler remained in
`RequestBodyReaderInputStreamAdapter.waitForData`. The runner rejected the
trial because its JVM required forced cleanup. Three predeclared isolated
diagnostic repeats passed; the complete comparison was then retried once to
continue validation. The original failure and thread dump remain in
`validation/smoke/attempts/comparison/0/25/raw-upload-h2/0/mu3/`, with the
investigation in `validation/mu3-upload-shutdown-finding.json`.
This is **not a fixed defect or a successful shutdown result**. The full runner
still stops if it recurs; retain it as a reference-edition failure instead of
repeatedly retrying for a clean benchmark.

If validation was interrupted, resume it with:

```sh
"$PYTHON" "$AUDITOR" validate --out "$CAMPAIGN"
```

**Wait until the user has released the PC for a quiet session.** Then run:

```sh
"$PYTHON" "$RUNNER" run --out "$CAMPAIGN" --host-state quiet --max-hours 8
```

Repeat the same command in each later quiet session. Completed groups are
skipped. The time limit is checked between complete three-edition groups, so
the last group may run beyond eight hours. `--max-groups 1` is available for a
single complete comparison. Use a persistent terminal session; do not launch
another copy because terminal output appears quiet. Inspect `status` or the
current group's worker log instead. The runner checkpoints after every trial
and group, preserves previous attempts, and stops before a new group if less
than 20 GiB of disk space remains. Keep the raw artifacts.

To request an orderly pause from another terminal:

```sh
"$PYTHON" "$RUNNER" pause --out "$CAMPAIGN"
```

It finishes the current three-edition group and exits. Wait for `CHECKPOINT`
before assuming measurement has stopped. If desktop use or another load
overlapped measurements, pause first and invalidate that session once the runner
has exited. Session IDs and timestamps are in `state.json`:

```sh
"$PYTHON" "$RUNNER" invalidate-session --out "$CAMPAIGN" \
  --session 0 --reason 'Desktop work overlapped this session'
"$PYTHON" "$RUNNER" run --out "$CAMPAIGN" --host-state quiet \
  --max-hours 8 --retry-failed
```

Replace `0` and the reason with the actual session and observation. This reruns
the whole affected comparisons and retains the original evidence and reason.
For an interrupted or failed group, inspect its `result.json`, worker log,
server log and cleanup records first. A documented resource limit is a valid
capacity observation and can continue automatically; a generic connection
error, incorrect body, missing population or cleanup failure cannot be silently
relabelled as one. Use `--retry-failed` only after identifying a transient cause.
Do not repeatedly retry a reproducible protocol/cleanup failure until it happens
to pass. A change to measurement or production code requires new frozen inputs and validation;
report the affected case rather than weakening its check or silently skipping it.

After `status` shows every group complete:

```sh
"$PYTHON" "$AUDITOR" audit --out "$CAMPAIGN"
"$PYTHON" "$RUNNER" report --out "$CAMPAIGN"
```

The deliverables are `report.html`, `summary.json`, `audit.json`,
`heap-selection.json`, `settings.json`, `plan.json` and `state.json`, with their
linked raw evidence. Do not call the baseline complete if the audit fails.
The independent validator/auditor is pinned in `audit-code-manifest.json`;
its receipts record the executed auditor hash. It reads both the per-result
and per-launch worker-policy records used by the different workload families.
Post-processing can be corrected without modifying the frozen measurement
controller, inputs or completed trials. Newly prepared campaigns can use their
own copied runner for both operations; the separate audit snapshot above is
the one tested for this existing campaign.

### How to summarize the results

Compare mu3, mu4-blocking and mu4-NIO **within the same workload and JDK**.
Report achieved throughput, p99 and available deeper tails, CPU per successful
response, whole-process memory, native threads and correctness/capacity outcomes.
Include both isolated and competing-work cases, and separate Java 21 platform
workers from Java 25 virtual workers. Report clear wins, regressions and cases
where the repeated ranges overlap; avoid one overall winner or averaging ratios
across unrelated workloads.

The report gives medians and ranges. `summary.json` also gives NIO/reference
ratios paired by repeat, with the number of eligible pairs. Busy, smoke, tuning,
failed, target-missing, generator-saturated and dependency-late observations are
excluded from speed rankings. Three eligible paired repeats are needed for a
settled speed comparison; fewer pairs do not establish that ranking. Resource
limits and excluded raw results remain visible, including all older attempts
in `state.json`.

Matched-load latency is comparable only when both editions achieved the same
target. A missed target remains a capacity finding. Native h2load latency is
not an open-loop arrival-to-completion SLA; downstream probes account for missed
scheduled slots, while the older writer probes allow bounded catch-up and save
dispatch lateness. Do not mix these latency definitions or claim WAN behavior
from loopback flow-control windows. Memory peaks are sampled process/cgroup
observations, not a heap-only comparison or proof of long-term leak freedom.

To prepare another campaign after an intentional code/runtime change, use a new
output directory and rerun validation. `prepare` refuses production inputs that
do not match the current NIO production source digest. The default source input
is the verified selected async snapshot from 23 October, not the original
pre-optimization NIO build. `--families` and `--cases` support separate diagnostic
campaigns; do not use them to silently reduce this full baseline.
