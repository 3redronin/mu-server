#!/usr/bin/env python3
"""Source-pinned three-way smoke/baseline runs, including paused-client isolation.

Uses the preserved native-load harness rather than changing earlier evidence.
Python dependencies are those in the sibling mu-conformance virtual environment.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import html
import importlib
import json
import os
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import sys
import time

ROLES = ("mu3", "blocking", "nio")
CORE = {
    "small": ("http1", 128, 32, 1, "fixed"),
    "h1bulk": ("http1", 1048576, 8, 1, "fixed"),
    "tlsbulk": ("https1", 1048576, 8, 1, "fixed"),
    "h2bulk": ("h2", 1048576, 8, 1, "fixed"),
    "chunked": ("http1", 1048576, 8, 1, "chunked"),
    "h2streams": ("h2", 128, 1, 32, "fixed"),
}
PAUSED_BYTES = 8 * 1024 * 1024


def read(path):
    return json.loads(Path(path).read_text())


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2))
    temporary.replace(path)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def cpu_set(text):
    result = set()
    for part in text.split(","):
        ends = list(map(int, part.split("-")))
        if len(ends) == 1:
            result.add(ends[0])
        elif len(ends) == 2 and ends[0] <= ends[1]:
            result.update(range(ends[0], ends[1] + 1))
        else:
            raise ValueError("Invalid CPU range: " + part)
    if not result or min(result) < 0:
        raise ValueError("CPU list must be nonempty and nonnegative")
    return result


def load_harness(directory):
    # One harness per invocation; callers cannot accidentally use a checkout's newer module.
    sys.path.insert(0, str(directory))
    module = importlib.import_module("compare_performance")
    if Path(module.__file__).resolve() != (directory / "compare_performance.py").resolve():
        raise RuntimeError("A different comparison harness is already loaded")
    return module


def verify_manifest(out, c):
    manifest = read(out / "manifest.json")
    for path, expected in manifest["files"].items():
        if digest(out / path) != expected:
            raise RuntimeError("Changed campaign input: " + path)
    c.process.TARGET = out / "frozen"
    for role in ROLES:
        c.process.load_build(role)  # Includes source, dependencies and all compiled resources.
    if digest(Path(manifest["tool"]["path"])) != manifest["tool"]["sha256"]:
        raise RuntimeError("Native generator changed")
    return manifest


def prepare(args):
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    workspace = args.workspace.resolve()
    original = workspace / "mu-conformance/target/nio-comparison-20261014"
    harness = out / "harness"
    shutil.copytree(original / "harness", harness, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    shutil.copytree(original / "benchmark/inputs", out / "inputs")
    shutil.copy2(__file__, out / "prepared_baseline.py")
    c = load_harness(harness)
    metadata_files = {
        role: original / "benchmark/frozen/build" / role / "build.json"
        for role in ("mu3", "blocking")
    }
    metadata_files["nio"] = args.nio_build.resolve()
    provenance = {}
    for role, filename in metadata_files.items():
        c.process.TARGET = filename.parents[2]
        original_metadata = c.process.load_build(role)
        metadata = json.loads(json.dumps(original_metadata))
        destination = out / "frozen/build" / role
        destination.mkdir(parents=True)
        production = Path(metadata["source"]["path"]) / "target/classes"
        shutil.copytree(production, destination / "production-classes")
        classes = destination / "performance-classes"
        classes.mkdir()
        jars = list(metadata["dependencies"])
        cp = os.pathsep.join(map(str, [classes, destination / "production-classes", *jars]))
        fixture = out / "inputs/PerformanceFixture.java"
        c.process.run_logged([c.process.jdk_path(21) / "bin/javac", "--release", "11", "-cp", cp,
                              "-d", classes, fixture], out / (role + "-javac.log"))
        metadata.update(classpath=cp, fixture_sources={str(fixture): digest(fixture)},
                        class_outputs={str(p): c.process.output_inventory(p)
                                       for p in (classes, destination / "production-classes")})
        save(destination / "build.json", metadata)
        provenance[role] = dict(input_metadata=str(filename), source=metadata["source"])
    current = c.process.identity(workspace / "mu4")
    if current["source_sha256"] == provenance["nio"]["source"]["source_sha256"]:
        provenance["nio"]["matching_checkout"] = current
    tool = workspace / "mu-conformance/target/performance-tools/prefix/bin/h2load"
    files = {str(p.relative_to(out)): digest(p) for base in (harness, out / "inputs")
             for p in sorted(base.rglob("*")) if p.is_file() and "__pycache__" not in p.parts}
    for role in ROLES:
        p = out / "frozen/build" / role / "build.json"
        files[str(p.relative_to(out))] = digest(p)
    manifest = dict(schema_version=1, created_epoch=time.time(), provenance=provenance, files=files,
                    tool=dict(path=str(tool), sha256=digest(tool)),
                    fixture_policy="One unchanged fixture source compiled separately against each edition")
    save(out / "manifest.json", manifest)
    verify_manifest(out, c)
    print(json.dumps(dict(prepared=str(out), sources=provenance)), flush=True)


def expected_mode(role, jdk):
    if role == "mu3":
        return "AUTO:false:NETTY"
    return ("PLATFORM:false:" if jdk == 21 else "VIRTUAL:true:") + ("NIO" if role == "nio" else "SOCKET")


def case(name):
    protocol, size, clients, streams, framing = CORE[name]
    return dict(id=name, protocol=protocol, kind="download", size=size, clients=clients,
                streams=streams, framing=framing, gzip=False, compressibility="high")


def plan(profile, paused, protocols, repeats):
    rows = []
    for repeat in range(repeats):
        for jdk in (21, 25):
            groups = [(name, case(name), 0, None) for name in CORE]
            for protocol in protocols:
                fast = dict(id="isolation-" + protocol, protocol=protocol, kind="download", size=128,
                            clients=4, streams=1, framing="fixed", gzip=False, compressibility="high")
                groups += [("isolation-" + protocol + suffix, fast, count, 200 if profile == "smoke" else 2000)
                           for suffix, count in (("-alone", 0), ("-paused", paused))]
            for index, (name, workload, count, rate) in enumerate(groups):
                # Cyclic triples change first/last edition; a quiet run repeats all three orders.
                offset = (index + repeat + (jdk == 25)) % len(ROLES)
                order = ROLES[offset:] + ROLES[:offset]
                for role in order:
                    rows.append(dict(key=f"{repeat}/{jdk}/{name}/{role}", repeat=repeat, jdk=jdk,
                                     role=role, workload=name, case=workload, paused=count, target_rps=rate))
    return rows


def accepted_peers(pid, port):
    inodes = set()
    for descriptor in (Path("/proc") / str(pid) / "fd").iterdir():
        try:
            target = os.readlink(descriptor)
            if target.startswith("socket:["):
                inodes.add(target[8:-1])
        except OSError:
            pass
    found = {}
    for family in ("tcp", "tcp6"):
        for line in (Path("/proc") / str(pid) / "net" / family).read_text().splitlines()[1:]:
            columns = line.split()
            if (int(columns[1].split(":")[1], 16) == port and columns[3] == "01"
                    and columns[9] in inodes):
                found[int(columns[2].split(":")[1], 16)] = dict(
                    send_queue_bytes=int(columns[4].split(":")[0], 16))
    return found


def require_peers(expected, observed):
    missing = set(expected) - set(observed)
    if missing:
        raise AssertionError(f"Paused peers were not retained: {sorted(missing)}")


def check_paused_headers(status, length, body_received):
    if status != "200" or length != str(PAUSED_BYTES):
        raise AssertionError(f"Unexpected paused response: {status}, {length}")
    if not 0 <= body_received < PAUSED_BYTES:
        raise AssertionError("Paused response already finished")


class PausedPeers:
    """HTTP/1 clients stop reading; HTTP/2 streams advertise zero receive credit.

    These are separate connections from the native foreground clients. This is
    connection isolation, not a same-connection HTTP/2 stream-fairness test.
    """
    def __init__(self, port, protocol, count):
        self.port, self.protocol, self.count = port, protocol, count
        self.sockets, self.records, self.h2_sessions = [], [], []

    def open(self):
        from h2.config import H2Configuration
        from h2.connection import H2Connection
        from h2.events import ResponseReceived, DataReceived, StreamEnded, StreamReset, ConnectionTerminated
        from h2.settings import SettingCodes
        for _ in range(self.count):
            sock = socket.socket()
            self.sockets.append(sock)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(8)
            sock.connect(("127.0.0.1", self.port))
            if self.protocol != "http1":
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE
                context.minimum_version = ssl.TLSVersion.TLSv1_3
                context.maximum_version = ssl.TLSVersion.TLSv1_3
                context.set_alpn_protocols(["h2" if self.protocol == "h2" else "http/1.1"])
                sock = context.wrap_socket(sock, server_hostname="localhost")
                self.sockets[-1] = sock
                if self.protocol == "h2" and sock.selected_alpn_protocol() != "h2":
                    raise AssertionError("Paused client did not negotiate HTTP/2")
            path = f"/download?size={PAUSED_BYTES}&compressibility=high"
            received = 0
            if self.protocol == "h2":
                peer = H2Connection(config=H2Configuration(client_side=True, header_encoding="utf-8"))
                peer.initiate_connection()
                peer.update_settings({SettingCodes.INITIAL_WINDOW_SIZE: 0})
                peer.send_headers(1, [(":method", "GET"), (":scheme", "https"), (":authority", "localhost"),
                                      (":path", path), ("accept-encoding", "identity")], end_stream=True)
                sock.sendall(peer.data_to_send())
                headers = None
                deadline = time.monotonic() + 8
                while headers is None:
                    sock.settimeout(max(.001, deadline - time.monotonic()))
                    data = sock.recv(16384)
                    if not data:
                        raise EOFError("Paused H2 peer closed before response headers")
                    received += len(data)
                    if received > 65536:
                        raise AssertionError("Excessive H2 data before paused response headers")
                    for event in peer.receive_data(data):
                        if isinstance(event, ResponseReceived):
                            headers = dict(event.headers)
                        elif isinstance(event, (DataReceived, StreamEnded, StreamReset, ConnectionTerminated)):
                            raise AssertionError("Unexpected progress/termination of zero-credit H2 stream")
                    pending = peer.data_to_send()
                    if pending:
                        sock.sendall(pending)
                check_paused_headers(headers.get(":status"), headers.get("content-length"), 0)
                body_received = 0
                self.h2_sessions.append(peer)
            else:
                sock.sendall((f"GET {path} HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: identity\r\n\r\n").encode())
                data = bytearray()
                deadline = time.monotonic() + 8
                while b"\r\n\r\n" not in data:
                    sock.settimeout(max(.001, deadline - time.monotonic()))
                    chunk = sock.recv(256)
                    if not chunk:
                        raise EOFError("Paused peer closed before response headers")
                    data.extend(chunk)
                    if len(data) > 65536:
                        raise AssertionError("Oversized paused response headers")
                head, body = bytes(data).split(b"\r\n\r\n", 1)
                lines = head.split(b"\r\n")
                headers = dict((name.decode().lower(), value.strip().decode())
                               for name, value in (line.split(b":", 1) for line in lines[1:]))
                body_received = len(body)
                check_paused_headers(lines[0].split()[1].decode(), headers.get("content-length"), body_received)
                received = len(data)
            self.records.append(dict(client_port=sock.getsockname()[1], response_body_bytes=PAUSED_BYTES,
                                     body_bytes_read=body_received, socket_api_bytes_read=received,
                                     receive_buffer_bytes=sock.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF),
                                     policy="zero H2 stream credit" if self.protocol == "h2" else "stop reading after headers",
                                     tls_cipher=sock.cipher() if isinstance(sock, ssl.SSLSocket) else None))

    def snapshot(self, pid):
        expected = {r["client_port"] for r in self.records}
        actual = accepted_peers(pid, self.port)
        require_peers(expected, actual)
        return dict(epoch=time.time(), expected=len(expected), retained=len(expected & actual.keys()),
                    total_server_established=len(actual), client_ports=sorted(expected),
                    send_queue_bytes={str(port): actual[port]["send_queue_bytes"] for port in expected})

    def verify_pending(self, snapshot):
        if self.protocol != "h2":
            if not all(snapshot["send_queue_bytes"].values()):
                raise AssertionError("A paused HTTP/1 download has no pending TCP output")
            return dict(policy="Every paused TCP connection still has queued output", verified=self.count)
        from h2.events import DataReceived, StreamEnded, StreamReset, ConnectionTerminated
        # Releasing one byte of credit proves each stream remains pending, not merely its TCP socket.
        for sock, peer in zip(self.sockets, self.h2_sessions):
            peer.increment_flow_control_window(1, stream_id=1)
            sock.sendall(peer.data_to_send())
            deadline, body, received = time.monotonic() + 8, bytearray(), 0
            while not body:
                sock.settimeout(max(.001, deadline - time.monotonic()))
                data = sock.recv(16384)
                if not data:
                    raise EOFError("Paused H2 stream did not resume")
                received += len(data)
                if received > 65536:
                    raise AssertionError("Excessive H2 data during resume probe")
                for event in peer.receive_data(data):
                    if isinstance(event, DataReceived):
                        body.extend(event.data)
                    elif isinstance(event, (StreamEnded, StreamReset, ConnectionTerminated)):
                        raise AssertionError("Paused H2 stream was retired before cancellation")
                pending = peer.data_to_send()
                if pending:
                    sock.sendall(pending)
            if len(body) != 1:
                raise AssertionError("H2 resume exceeded the single byte of stream credit")
        return dict(policy="Each zero-credit H2 stream resumed for exactly one byte", verified=self.count)

    def close(self):
        for sock in self.sockets:
            with contextlib.suppress(OSError):
                sock.close()
        self.sockets.clear()


def exclusions(row, host_state, profile):
    result = []
    if host_state == "busy":
        result.append("PC in use; external contention uncontrolled")
    if profile == "smoke":
        result.append("Short smoke run; insufficient warm-up/repeats for a performance conclusion")
    if row.get("outcome") != "pass":
        result.append("Correctness/resource check failed")
    for key in ("generator_busy", "connections_incomplete"):
        if row.get(key):
            result.append(key)
    if row.get("target_rate_met") is False:
        result.append("Target foreground rate was not achieved")
    return result


def host_snapshot():
    return dict(epoch=time.time(), loadavg=Path("/proc/loadavg").read_text().strip(),
                cpu=Path("/proc/stat").read_text(), memory=Path("/proc/meminfo").read_text(),
                cpu_pressure=Path("/proc/pressure/cpu").read_text(),
                memory_pressure=Path("/proc/pressure/memory").read_text())


def run_trial(c, out, directory, item, spec, tool):
    base = c.Server
    original_count = c.accepted_connections
    role, jdk = item["role"], item["jdk"]
    mode = "AUTO" if role == "mu3" else ("PLATFORM" if jdk == 21 else "VIRTUAL")
    active = None

    class Instrumented(base):
        def __init__(self, *args, **kwargs):
            nonlocal active
            self.paused = None
            self.paused_snapshots = {}
            super().__init__(*args, jdk_version=jdk, java_options=[f"-Dcomparison.threading={mode}"], **kwargs)
            active = self

        def ready(self, *args, **kwargs):
            super().ready(*args, **kwargs)
            port = self.record["ready"]["port"]
            with c.Wire(port, tls=self.protocol != "http1", alpn=["h2" if self.protocol == "h2" else "http/1.1"]) as wire:
                if self.protocol == "h2":
                    peer = c.H2(wire)
                    peer.send(1, "/mode")
                    response = peer.finish([1])[0]
                else:
                    wire.send(c.request("/mode"))
                    response = wire.response()
            expected = expected_mode(role, jdk)
            if response["status"] != 200 or response["body_preview"] != expected:
                raise AssertionError(("Worker/transport mismatch", expected, response))
            self.record["mode_check"] = expected
            if item["paused"]:
                self.paused = PausedPeers(port, self.protocol, item["paused"])
                self.paused.open()
                self.record["paused_clients"] = self.paused.records
                self.paused_snapshots["established"] = self.paused.snapshot(self.pid)
            return self

        def stats(self):
            values = super().stats()
            if self.paused and self.phase in ("measurement", "verification-after", "recovery"):
                self.paused_snapshots[self.phase + "-" + str(len(self.paused_snapshots))] = self.paused.snapshot(self.pid)
            return values

        def close(self):
            # Close only this trial's clients; verify service recovery before stopping the JVM.
            if self.paused:
                try:
                    self.paused_snapshots["before-client-close"] = self.paused.snapshot(self.pid)
                    self.record["paused_pending_check"] = self.paused.verify_pending(self.paused_snapshots["before-client-close"])
                    self.paused.close()
                    self.record["post_cancel_check"] = c.check(self, item["case"], out)
                except Exception as error:
                    self.record["paused_cleanup_error"] = f"{type(error).__name__}: {error}"
                finally:
                    self.paused.close()
                    self.record["paused_snapshots"] = self.paused_snapshots
            super().close()

    def foreground_connections(pid, port):
        # The native harness expects only foreground connections; retain the raw count too.
        count = original_count(pid, port)
        if active is not None and active.paused:
            active.paused_snapshots["native-start"] = active.paused.snapshot(pid)
            return count - item["paused"]
        return count

    c.Server = Instrumented
    c.accepted_connections = foreground_connections
    before = host_snapshot()
    try:
        row = c.native_trial(out, directory, role, item["case"], tool,
                             warmup=spec["warmup"], duration=spec["duration"], rps=item["target_rps"])
    finally:
        c.Server = base
        c.accepted_connections = original_count
    row.update(item)
    row["host_before"], row["host_after"] = before, host_snapshot()
    row["host_state"] = spec["host_state"]
    if row.get("launch", {}).get("paused_cleanup_error"):
        row["error"] = row["launch"]["paused_cleanup_error"]
        row["outcome"] = "paused-client retention/recovery failure"
    if item["target_rps"] is not None:
        # Record achieved rate; a short/busy run cannot attribute a miss to the server.
        row["target_rate_met"] = row.get("measurement", {}).get("requests_per_second", 0) >= .9 * item["target_rps"]
    row["performance_exclusions"] = exclusions(row, spec["host_state"], spec["profile"])
    save(directory / "result.json", row)
    return row


def report(run):
    ledger = read(run / "results.json")
    spec = ledger["spec"]
    esc = html.escape
    notice = ("BUSY-PC SMOKE EVIDENCE — results may be affected by concurrent desktop use. "
              "Use this run to check the harness and correctness, not to rank implementations."
              if spec["host_state"] == "busy" or spec["profile"] == "smoke"
              else "Controlled-run candidate evidence. Inspect exclusions, achieved rates and repeats before comparing performance.")
    parts = ['<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">',
             '<title>Mu three-way baseline</title><style>body{font:16px system-ui;max-width:1400px;margin:30px auto;padding:0 20px;color:#21313d}table{border-collapse:collapse;width:100%;font-size:14px}td,th{padding:9px;border-bottom:1px solid #ccd6df;text-align:left}th{background:#e9eff5}.notice{background:#fff0ce;padding:16px}code{overflow-wrap:anywhere}.scroll{overflow:auto}</style>',
             '<h1>Mu3 / Mu4-blocking / Mu4-NIO baseline</h1><p class="notice">'+notice+'</p>',
             '<p>'+esc(str(len(ledger["trials"])))+' / '+str(len(ledger["plan"]))+' trials saved; complete: '+str(ledger["complete"])+'. '
             '<a href="results.json">Raw ledger</a> · <a href="../../manifest.json">Source and input manifest</a></p>',
             '<p>Java 21: both Mu4 editions explicitly use PLATFORM workers. Java 25: both explicitly use VIRTUAL workers. Mu3 uses its Netty configuration. Every JVM verifies its actual mode over HTTP. '
             'All editions use the same fixture, 512 MiB heap and 1 GiB server cgroup with swap disabled. Server CPUs '+esc(spec["server_cpus"])+', generator CPUs '+esc(spec["generator_cpus"])+'. '
             'Warm-up '+str(spec["warmup"])+' s; measurement '+str(spec["duration"])+' s; repeats '+str(spec["repeats"])+'.</p>',
             '<p>Isolation pairs compare four foreground clients targeting '+str(200 if spec["profile"] == "smoke" else 2000)+' total small responses/s, alone and alongside '
             +str(spec["paused_connections"])+ ' paused 8 MiB downloads on separate connections. HTTP/1 clients stop reading after headers; HTTP/2 streams advertise zero receive credit. '
             'This does not test fairness between streams sharing one HTTP/2 connection. Paused peers must remain established through load and verification; a fresh foreground response must succeed after they are cancelled.</p>',
             '<p>Payload hashes are checked before and after each native measurement. Request/status logs are reconciled during load. Latency is native request latency, not a validated arrival-to-completion open-loop latency curve. '
             'Both failed trials and performance exclusions remain visible. No conclusions about unbounded application async queues are drawn.</p>',
             '<div class="scroll"><table><thead><tr><th>Runtime / workload / repeat</th><th>Edition / mode</th><th>Check</th><th>Responses/s</th><th>p99 ms</th><th>CPU µs/response</th><th>Peak MiB</th><th>Exclusions</th><th>Evidence</th></tr></thead><tbody>']
    def number(value, places=1):
        return "—" if value is None else f"{value:,.{places}f}"
    for row in ledger["trials"]:
        m = row.get("measurement", {})
        relative = Path(row["path"]).relative_to(Path("runs") / run.name)
        parts.append('<tr><td>'+esc(f'{row["jdk"]} / {row["workload"]} / {row["repeat"]}')+'</td><td>'+esc(row["role"]+' / '+row.get("launch", {}).get("mode_check", "unverified"))+'</td>'
                     '<td>'+esc(row["outcome"])+'</td>'+''.join('<td>'+number(v, d)+'</td>' for v, d in [
                         (m.get("requests_per_second"), 0), (m.get("p99_ms"), 3), (m.get("cpu_us_per_request"), 1), (row.get("peak_mib"), 1)])
                     +'<td>'+esc('; '.join(row["performance_exclusions"]))+'</td><td><a href="'+esc(str(relative / "result.json"))+'">raw trial</a></td></tr>')
    parts.append('</tbody></table></div></html>')
    (run / "report.html").write_text('\n'.join(parts))


def run(args):
    out = args.out.resolve()
    c = load_harness(out / "harness")
    manifest = verify_manifest(out, c)
    server_cpus, generator_cpus = cpu_set(args.server_cpus), cpu_set(args.generator_cpus)
    if server_cpus & generator_cpus:
        raise ValueError("Server and generator CPU sets overlap")
    available = os.sched_getaffinity(0)
    if not (server_cpus | generator_cpus) <= available:
        raise ValueError("Requested CPUs are unavailable")
    run_dir = out / "runs" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    shutil.copy2(__file__, run_dir / "executed_baseline.py")
    spec = dict(profile=args.profile, host_state=args.host_state, server_cpus=args.server_cpus,
                generator_cpus=args.generator_cpus, warmup=1 if args.profile == "smoke" else 10,
                duration=3 if args.profile == "smoke" else 20,
                repeats=args.repeats or (1 if args.profile == "smoke" else 3),
                paused_connections=args.paused_connections if args.paused_connections is not None else (8 if args.profile == "smoke" else 128),
                mixed_protocols=args.mixed_protocols,
                controller_sha256=digest(__file__), manifest_sha256=digest(out / "manifest.json"))
    if spec["repeats"] < 1 or not 1 <= spec["paused_connections"] <= 1024:
        raise ValueError("Require positive repeats and 1..1024 paused connections")
    planned = plan(args.profile, spec["paused_connections"], args.mixed_protocols, spec["repeats"])
    ledger = dict(spec=spec, started_epoch=time.time(), plan=planned, trials=[], complete=False)
    save(run_dir / "results.json", ledger)
    save(run_dir / "hardware.json", dict(lscpu=subprocess.check_output(["lscpu", "-J"], text=True),
                                        before=host_snapshot(), python=sys.version))
    report(run_dir)
    c.SERVER_CPUS, c.GENERATOR_CPUS = args.server_cpus, args.generator_cpus
    os.sched_setaffinity(0, generator_cpus)
    for item in planned:
        directory = run_dir / "trials" / item["key"]
        print("START", item["key"], flush=True)
        row = run_trial(c, out, directory, item, spec, Path(manifest["tool"]["path"]))
        ledger["trials"].append(row)
        save(run_dir / "results.json", ledger)
        report(run_dir)
        # Keep the failed run as evidence. Fix/retry in a separate run directory.
        if row["outcome"] != "pass":
            raise RuntimeError(f'Trial failed: {item["key"]}: {row.get("error", row["outcome"])}')
    verify_manifest(out, c)
    ledger.update(complete=True, finished_epoch=time.time(), final_identity_audit="pass")
    save(run_dir / "results.json", ledger)
    report(run_dir)
    print("COMPLETE", run_dir / "report.html", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    workspace = Path(__file__).resolve().parents[3]
    p = sub.add_parser("prepare")
    p.add_argument("--workspace", type=Path, default=workspace)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--nio-build", type=Path, default=workspace / "nonblocking-validation/performance-20261017/async-turn/frozen/build/nio/build.json")
    p = sub.add_parser("run")
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--run-id", required=True)
    p.add_argument("--profile", choices=("smoke", "full"), default="smoke")
    p.add_argument("--host-state", choices=("busy", "quiet"), required=True)
    p.add_argument("--server-cpus", default="0-3,8-11")
    p.add_argument("--generator-cpus", default="4-7,12-15")
    p.add_argument("--repeats", type=int)
    p.add_argument("--paused-connections", type=int)
    p.add_argument("--mixed-protocols", nargs="+", choices=("http1", "https1", "h2"), default=["https1", "h2"])
    p = sub.add_parser("report")
    p.add_argument("--run", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args)
    elif args.action == "run":
        if Path(args.run_id).name != args.run_id or args.run_id in (".", ".."):
            parser.error("run-id must be a single directory name")
        run(args)
    else:
        report(args.run.resolve())


if __name__ == "__main__":
    main()
