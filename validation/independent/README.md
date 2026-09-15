# Repeatable independent conformance

This campaign runs **h2spec 2.6.0** and **Autobahn Testsuite 25.10.1** against separately built Mu3/Mu4 JVMs over local sockets. Mu4 is the candidate; Mu3 is the Netty reference. The run does not modify either server or require a Docker daemon.

Requirements: Linux amd64, Python 3.11+, Java 11/17/21/25, Maven, git, GNU tar and bubblewrap (`bwrap`, with unprivileged namespaces available). Python uses only its standard library. Set `MU_JAVA_11`, `MU_JAVA_17`, `MU_JAVA_21`, `MU_JAVA_25` to the respective JDK homes if they are not installed under `~/.local/share/mise/installs/java/temurin-<major>`.

## Setup and full run

From the repository containing `validation/`:

```sh
python3 validation/independent/prepare.py
python3 validation/independent/run.py \
  --build --mu3 /absolute/path/to/clean-mu3 --mu4 /absolute/path/to/clean-mu4 \
  --jdks 11,17,21,25 \
  --output /absolute/path/to/new-conformance-results
```

Use clean isolated checkouts at explicit commits. `--build` compiles the shared public-API fixture against each server using Java 21 and `--release 11`; Mu3 uses its Netty 4.1 profile. Maven repository verification remains a separate release check. Server sources, fixture sources, class files and dependencies are hashed; stale builds are rejected. Build artifacts default to `validation/target/independent-build`; override with `--build-dir` in both build and replay commands. Do not rebuild fixtures during a campaign.

Tool setup is the only step that downloads external conformance binaries. h2spec's archive and executable have embedded SHA-256 pins. Autobahn uses the official image `crossbario/autobahn-testsuite@sha256:519915fb568b04c9383f70a1c405ae3ff44ab9e35835b085239c258b6fac3074`. The manifest, configuration and each layer are verified. Installation retains the image metadata and runtime file inventory. The runner verifies the inventory before starting Autobahn.

The image is extracted locally and executed with bubblewrap: image read-only, report directory writable, temporary scratch space, and shared networking to reach loopback fixtures. There is no Docker daemon, privileged container, system install or permission change. Setup uses roughly 1.5 GB for the image and cached layers. Interrupted downloads resume and are checksum-verified; partial extraction is explicitly rejected. Tools default to `validation/target/independent-tools`; `--tools-dir` overrides that for setup and runs.

## Replays

Reuse unchanged builds and tools, always writing to a fresh output directory:

```sh
python3 validation/independent/run.py --tools h2spec --jdks 21 \
  --output /absolute/path/to/new-h2-results
python3 validation/independent/run.py --tools autobahn --jdks 21 \
  --autobahn-cases 1.2.7,2.6 \
  --output /absolute/path/to/new-websocket-replay
```

`--servers mu4` omits the reference explicitly. Default is `mu3,mu4`. A focused run records its selected tools/JDKs/cases and cannot establish full matrix coverage. Each tool uses its own server fixture, has a configurable bounded timeout (`--timeout`, default 1,200 seconds), gets a post-run ordinary HTTP health check, and is followed by server shutdown.

Exit codes: **0** means the candidate passed every selected tool/case and the requested matrix completed; **1** means candidate failure or review-required verdicts; **2** means missing tools, incomplete reports, execution failure without a valid report, or an incomplete matrix. Startup/build/configuration errors also terminate nonzero. Mu3 case failures are retained but do not fail the candidate gate; missing Mu3 execution does make the campaign incomplete.

## Evidence and interpretation

The output contains `report.md`, `results.json`, and `junit.xml`, plus each original h2spec XML or Autobahn JSON/HTML report, exact command, server log and build identity. `expected-cases.json` comes from the pinned Autobahn case-selection code; every expected case must appear with matching agent/ID and both protocol/close verdicts. Missing detail reports, missing verdicts, empty/truncated reports and skipped h2spec cases cannot pass. Harness Python/Java sources are snapshotted under `harness/`.

Autobahn `NON-STRICT` and `INFORMATIONAL` verdicts are reported as **review**, distinct from failures and strict passes. They require case-specific assessment; an informational result does not by itself prove a server bug. No result is silently waived. JUnit preserves all non-pass outcomes, including reference failures; the process exit code is the candidate gate described above.

The explicit `independent` fixture profile uses a 16 MiB WebSocket frame limit, a 16 MiB message limit where supported by the API, and 60-second WebSocket read timeout. Both use ordinary echo handlers. Mu3 exposes fragment callbacks; Mu4's SimpleWebSocket assembles messages. This exercises the public APIs and should be considered when diagnosing reference Unicode/fragmentation failures. It is not a server-defaults campaign. HTTP limits remain 8 KiB headers/URL and 24 MiB request body.

## Coverage limits

- h2spec runs all 147 strict RFC7540/RFC7541/generic cases over HTTPS with ALPN. Its `-k` flag permits the fixture's self-signed certificate. It does **not** test certificate trust, all RFC9113 changes, HTTP/1 conformance, or cleartext HTTP/2 in this profile.
- Autobahn's default core profile has 247 cases. It excludes `9.*` (large-message/performance tests) and `12.*`/`13.*` (permessage-deflate extension tests). The exclusions are recorded in every report. No per-agent exclusions are applied.
- WebSocket traffic here uses `ws://`. WSS, compression extensions, broader capacity/backpressure and a multi-day soak remain separate release work.
- Matching wire bytes or the reference's verdict is not required. Compare decoded behavior and protocol requirements; classify better/worse/indifferent differences separately.

Upstream references: [h2spec](https://github.com/summerwind/h2spec), [Autobahn Testsuite](https://github.com/crossbario/autobahn-testsuite).

Run the runner's report-integrity tests from `validation/`:

```sh
python3 -m unittest discover -s tests -p test_independent.py
```
