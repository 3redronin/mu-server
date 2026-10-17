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
harness checks passed. The longer quiet-host profile has not been run.
