#!/usr/bin/env python3
"""Matched idle-connection density with independent active foreground traffic."""
import argparse
import collections
import hashlib
import html
import json
import os
from pathlib import Path
import resource
import signal
import socket
import ssl
import statistics
import subprocess
import time

import baseline as b


def configure_descriptors(max_count):
    before, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    desired = (before if before == resource.RLIM_INFINITY else
               max(before, 8192) if hard == resource.RLIM_INFINITY else min(hard, max(before, 8192)))
    if desired != resource.RLIM_INFINITY and desired < max_count + 512:
        raise ValueError('Client descriptor hard limit is insufficient for the cohort plus measurement overhead')
    resource.setrlimit(resource.RLIMIT_NOFILE, (desired, hard))
    return dict(soft_before=before, soft=desired, hard=hard)


def response(reader, expected):
    """Read exactly one bounded HTTP/1 response, retaining the next response."""
    status = reader.readline(8193)
    if len(status) > 8192 or not status.endswith(b'\r\n') or status.split()[1:2] != [b'200']:
        raise AssertionError('Invalid idle-peer response status: ' + repr(status[:100]))
    headers, total = {}, len(status)
    while True:
        line = reader.readline(8193); total += len(line)
        if not line or len(line) > 8192 or total > 65536 or not line.endswith(b'\r\n'):
            raise AssertionError('Truncated or oversized idle-peer headers')
        if line == b'\r\n': break
        name, value = line[:-2].split(b':', 1)
        name = name.lower()
        if name in headers: raise AssertionError('Duplicate idle-peer response header')
        headers[name] = value.strip().lower()
    if headers.get(b'connection') == b'close': raise AssertionError('Idle peer is not keep-alive')
    body = bytearray()
    if headers.get(b'transfer-encoding') == b'chunked':
        if b'content-length' in headers: raise AssertionError('Ambiguous idle-peer framing')
        while True:
            line = reader.readline(128)
            if not line.endswith(b'\r\n'): raise AssertionError('Invalid chunk header')
            count = int(line.split(b';', 1)[0], 16)
            if count < 0 or len(body) + count > len(expected): raise AssertionError('Oversized idle-peer body')
            if not count:
                if reader.readline(8193) != b'\r\n': raise AssertionError('Unexpected idle-peer trailers')
                break
            data = reader.read(count)
            if len(data) != count or reader.read(2) != b'\r\n': raise AssertionError('Truncated chunk')
            body.extend(data)
    else:
        if headers.get(b'content-length') != str(len(expected)).encode():
            raise AssertionError('Invalid idle-peer content length')
        body.extend(reader.read(len(expected)))
    if bytes(body) != expected: raise AssertionError('Idle-peer payload mismatch')


class IdlePeers:
    def __init__(self, port, protocol, count, expected):
        self.port, self.protocol, self.count, self.expected = port, protocol, count, expected
        self.peers, self.ports = [], set()
        self.resumed = 0

    def probe(self, sock, reader):
        sock.settimeout(5)
        sock.sendall(b'GET /download?size=128&compressibility=high HTTP/1.1\r\n'
                     b'Host: localhost\r\nAccept-Encoding: identity\r\n\r\n')
        response(reader, self.expected)

    def open(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        context.set_alpn_protocols(['http/1.1'])
        started = time.monotonic()
        for _ in range(self.count):
            if time.monotonic() - started > 120: raise TimeoutError('Idle cohort setup exceeded 120 seconds')
            sock = socket.create_connection(('127.0.0.1', self.port), timeout=5)
            reader = None
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                if self.protocol == 'https1':
                    sock = context.wrap_socket(sock, server_hostname='localhost')
                    if sock.version() != 'TLSv1.3' or sock.cipher()[0] != 'TLS_AES_128_GCM_SHA256':
                        raise AssertionError('Unexpected idle-peer TLS negotiation')
                reader = sock.makefile('rb')
                self.probe(sock, reader)
                self.peers.append((sock, reader)); self.ports.add(sock.getsockname()[1])
            except BaseException:
                if reader: reader.close()
                sock.close(); raise

    def snapshot(self, pid):
        actual = b.accepted_peers(pid, self.port)
        b.require_peers(self.ports, actual)
        return dict(expected=self.count, established=len(self.peers), retained=len(self.ports & actual.keys()),
                    total_server_established=len(actual), epoch=time.time())

    def verify_all(self):
        if len(self.peers) != self.count: raise AssertionError('Incomplete idle cohort cannot be verified')
        for sock, reader in self.peers:
            self.probe(sock, reader); self.resumed += 1
        return dict(verified=self.resumed, body_sha256=hashlib.sha256(self.expected).hexdigest())

    def close(self):
        for sock, reader in self.peers:
            reader.close(); sock.close()
        self.peers.clear()


def shutdown_error(launch):
    status = launch.get('unit_status', {})
    if launch.get('forced_cleanup_stop'): return 'JVM needed forced cleanup after the shutdown observation'
    if status.get('MainPID') != '0' or status.get('Result') != 'success' or status.get('ExecMainCode') != '1' or status.get('ExecMainStatus') != '0':
        return 'Normal JVM exit was not verified before resetting the unit'
    return None


def install_shutdown_observer(c):
    """Capture actual JVM exit, rather than treating systemd-run's exit as JVM exit."""
    base = c.Server
    class Observed(base):
        def close(self):
            if self.record.get('ready'):
                try: self.control('STOP', 'STOPPED')
                except (BrokenPipeError, RuntimeError, TimeoutError, OSError) as error:
                    self.record['stop_control_error'] = repr(error)
            started, captured = time.monotonic(), False
            while time.monotonic() - started < 10:
                self.record['unit_status'] = c.properties(self.unit)
                if self.record['unit_status'].get('MainPID') == '0': break
                if not captured and time.monotonic() - started >= 5 and self.pid:
                    captured = True
                    try:
                        result = subprocess.run([str(c.process.jdk_path(self.record['jdk_version']) / 'bin/jcmd'),
                                                 str(self.pid), 'Thread.print'], capture_output=True, text=True, timeout=5)
                        (self.directory / 'shutdown-threads.txt').write_text(result.stdout + '\n' + result.stderr)
                    except Exception as error: (self.directory / 'shutdown-threads.txt').write_text(repr(error))
                time.sleep(.1)
            self.record['shutdown_observation_seconds'] = time.monotonic() - started
            self.record['forced_cleanup_stop'] = self.record.get('unit_status', {}).get('MainPID') != '0'
            self.stop_monitor.set(); self.monitor.join(timeout=6)
            c.systemctl('stop', self.unit)
            self.record['status_after_systemctl_stop'] = c.properties(self.unit)
            if self.record['forced_cleanup_stop']: self.record['unit_status'] = self.record['status_after_systemctl_stop']
            c.systemctl('reset-failed', self.unit)
            try: self.launcher.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(self.launcher.pid, signal.SIGKILL); self.launcher.wait(timeout=3)
            self.stop_reader.set(); self.reader.join(timeout=2)
            self.record.update(launcher_exit_code=self.launcher.returncode, ended_epoch=time.time(),
                               uploads_remaining=[p.name for p in (self.directory / 'uploads').iterdir() if p.is_file()])
            for stream in (self.stdin, self.launcher.stdout, self.log): stream.close()
            c.save(self.directory / 'launch.json', self.record)
    c.Server = Observed


def plan(counts, protocols, jdks, repeats, rate):
    rows = []
    for repeat in range(repeats):
        for jdk in jdks:
            for index, (protocol, count) in enumerate((p, n) for p in protocols for n in counts):
                case = dict(id='density-' + protocol, protocol=protocol, kind='download', size=128,
                            clients=16, streams=1, framing='fixed', gzip=False, compressibility='high')
                offset = (repeat + index + (jdk == 25)) % 3
                for role in b.ROLES[offset:] + b.ROLES[:offset]:
                    rows.append(dict(key=f'{repeat}/{jdk}/{protocol}/idle-{count}/{role}', repeat=repeat,
                                     jdk=jdk, role=role, workload=f'{protocol}-idle-{count}', case=case,
                                     idle=count, paused=0, target_rps=rate))
    return rows


def native_with_cleanup(c, tool, invoke):
    """Reap this trial's generator and log reader even on early fixture failure.

    The frozen harness starts the generator before its first stats call. An
    exception there bypasses its inner cleanup block. Keep the frozen inputs
    intact and guard that lifecycle here; never terminate unrelated processes.
    """
    popen, thread_factory = c.subprocess.Popen, c.threading.Thread
    owned, readers, records = [], [], []
    row = None

    def tracked_popen(command, *args, **kwargs):
        is_generator = isinstance(command, (list, tuple)) and str(tool) in command
        if is_generator and not kwargs.get('start_new_session'):
            raise AssertionError('Native generator must own a process group')
        process = popen(command, *args, **kwargs)
        if is_generator: owned.append(process)
        return process

    def tracked_thread(*args, **kwargs):
        thread = thread_factory(*args, **kwargs)
        if getattr(kwargs.get('target'), '__name__', '') == 'read_generator': readers.append(thread)
        return thread

    c.subprocess.Popen, c.threading.Thread = tracked_popen, tracked_thread
    try:
        row = invoke()
        return row
    finally:
        c.subprocess.Popen, c.threading.Thread = popen, thread_factory
        for process in owned:
            forced = process.poll() is None
            if forced:
                try: os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                try: process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    try: os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    process.wait(timeout=3)
            else: process.wait(timeout=3)
            records.append(dict(pid=process.pid, forced=forced, exit_code=process.returncode,
                                reaped_epoch=time.time()))
        for thread in readers:
            thread.join(timeout=3)
            if thread.is_alive(): raise RuntimeError('Native log reader did not stop; refusing another trial')
        for process in owned:
            if process.stdout and not process.stdout.closed: process.stdout.close()
        if row is not None:
            row['generator_lifecycle'] = records
            if row.get('outcome') == 'pass' and any(r['forced'] for r in records):
                row.update(outcome='generator cleanup failure', error='Native generator required forced cleanup')


def trial(c, inputs, directory, item, settings, tool):
    base, original_count, original_native = c.Server, c.accepted_connections, c.native_trial
    active = None
    expected = c.payload(inputs, 128, 'high')
    class Density(base):
        def __init__(self, *args, **kwargs):
            nonlocal active
            self.pool = None
            super().__init__(*args, **kwargs); active = self

        def ready(self, *args, **kwargs):
            super().ready(*args, **kwargs)
            soft, hard = resource.prlimit(self.pid, resource.RLIMIT_NOFILE)
            self.record['descriptor_limits'] = dict(soft=soft, hard=hard)
            if soft != resource.RLIM_INFINITY and soft < item['idle'] + 512:
                raise RuntimeError('Server descriptor budget is insufficient for this workload')
            self.pool = IdlePeers(self.record['ready']['port'], self.protocol, item['idle'], expected)
            self.record['idle_cohort'] = dict(target=item['idle'], established=0, snapshots={})
            started = time.monotonic()
            try: self.pool.open()
            finally:
                self.record['idle_cohort'].update(established=len(self.pool.peers), setup_seconds=time.monotonic()-started)
            self.record['idle_cohort']['snapshots']['established'] = self.pool.snapshot(self.pid)
            return self

        def stats(self):
            if self.pool:
                self.record['idle_cohort']['snapshots'][self.phase] = self.pool.snapshot(self.pid)
            return super().stats()

        def close(self):
            try:
                if self.pool and len(self.pool.peers) == self.pool.count:
                    self.phase = 'idle-resumption'
                    self.record['idle_cohort']['snapshots']['before-resumption'] = self.pool.snapshot(self.pid)
                    self.record['idle_cohort']['resumption'] = self.pool.verify_all()
                    self.record['idle_cohort']['snapshots']['after-resumption'] = self.pool.snapshot(self.pid)
                    self.pool.close()
                    self.record['post_idle_close_check'] = c.check(self, item['case'], inputs)
            except Exception as error: self.record['idle_verification_error'] = repr(error)
            finally:
                if self.pool: self.pool.close()
                super().close()

    def count(pid, port):
        if active and active.pool:
            active.pool.snapshot(pid)
            return original_count(pid, port) - len(active.pool.peers)
        return original_count(pid, port)

    def native(*args, **kwargs):
        return native_with_cleanup(c, tool, lambda: original_native(
            *args, cap=settings['cap_mib'], heap=settings['heap'], **kwargs))

    c.Server, c.accepted_connections, c.native_trial = Density, count, native
    try: row = b.run_trial(c, inputs, directory, item, settings, tool)
    finally: c.Server, c.accepted_connections, c.native_trial = base, original_count, original_native
    launch = row.get('launch', {})
    problems = [launch.get('idle_verification_error'), shutdown_error(launch)]
    if row['outcome'] == 'pass' and any(problems):
        row.update(outcome='cohort/cleanup verification failure', error='; '.join(p for p in problems if p))
    row['target_rate_met'] = row.get('measurement', {}).get('requests_per_second', 0) >= .98 * item['target_rps']
    row['performance_exclusions'] = b.exclusions(row, settings['host_state'], settings['profile'])
    b.save(directory / 'result.json', row)
    return row


def report(run):
    ledger = b.read(run / 'results.json'); groups = collections.defaultdict(list)
    for row in ledger['trials']: groups[(row['jdk'], row['case']['protocol'], row['idle'], row['role'])].append(row)
    parts = ['<!doctype html><meta charset="utf-8"><title>Idle connection density</title>',
             '<style>body{font:16px system-ui;max-width:1400px;margin:30px auto;padding:20px}table{border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ccc;text-align:right}td:first-child,th:first-child{text-align:left}</style>',
             '<h1>Mu3 / Mu4 blocking / Mu4 NIO: idle connection density</h1>',
             '<p>Identical completed keep-alive requests, followed by idle connections and a separate 10,000/s foreground target. Every idle socket must survive and serve a fresh exact payload after timing. This measures resource cost and foreground isolation, not maximum connection capacity or an open-loop latency SLA.</p>',
             '<p>' + html.escape(json.dumps(ledger['spec'])) + '</p>',
             f'<p>{len(ledger["trials"])} / {len(ledger["plan"])} trials; complete: {ledger["complete"]}. <a href="results.json">Ledger</a> · <a href="../../manifest.json">Frozen inputs</a></p>',
             '<p>Smoke and busy-host rates are excluded from performance conclusions. Setup failures remain visible; partial cohorts never count as successful measurements. Observed rates/latency/GC retain excluded measurements as diagnostics; they do not become valid rates. Charged peak includes setup and resumption; measurement memory is sampled current charge. Native threads are sampled OS threads.</p>',
             '<table><tr><th>JDK / protocol / idle / edition</th><th>Verified runs / saved</th><th>Valid rates</th><th>Valid req/s</th><th>Valid p99 ms</th><th>Observed req/s</th><th>Observed p99 ms</th><th>GC ms</th><th>Native threads</th><th>Measurement MiB</th><th>Lifetime peak MiB</th></tr>']
    summaries = []
    for (jdk, protocol, idle, role), rows in groups.items():
        good = [r for r in rows if r['outcome'] == 'pass' and not r['performance_exclusions']]
        correct = [r for r in rows if r['outcome'] == 'pass']
        def median(values): return statistics.median(values) if values else None
        rates = {k: median([r['measurement'][k] for r in good]) for k in ('requests_per_second', 'p99_ms')}
        observed = {k: median([r['measurement'][k] for r in rows if k in r.get('measurement', {})])
                    for k in ('requests_per_second', 'p99_ms', 'gc_millis')}
        threads = median([max(s.get('process', {}).get('os_threads', 0) for s in r['launch']['samples'] if s['phase'] == 'measurement') for r in correct])
        memory = median([r['measurement_peak_mib'] for r in correct]); peak = median([r['peak_mib'] for r in rows])
        entry = dict(jdk=jdk, protocol=protocol, idle=idle, role=role, saved=len(rows), correct=len(correct), valid=len(good),
                     **rates, observed=observed, native_threads=threads, measurement_peak_mib=memory, peak_mib=peak)
        summaries.append(entry)
        def fmt(v, n=1): return '—' if v is None else f'{v:,.{n}f}'
        values = [f'{jdk} / {protocol} / {idle} / {role}', f'{len(correct)}/{len(rows)}', str(len(good)),
                  fmt(rates['requests_per_second'], 0), fmt(rates['p99_ms'], 3),
                  fmt(observed['requests_per_second'], 0), fmt(observed['p99_ms'], 3),
                  fmt(observed['gc_millis'], 0), fmt(threads, 0), fmt(memory), fmt(peak)]
        parts.append('<tr>' + ''.join('<td>' + html.escape(v) + '</td>' for v in values) + '</tr>')
    parts.append('</table><h2>All trials and exclusions</h2><ul>')
    for row in ledger['trials']:
        link = 'trials/' + row['key'] + '/result.json'
        text = row['key'] + ': ' + row['outcome'] + '; ' + str(row.get('error', '')) + '; ' + ', '.join(row['performance_exclusions'])
        text += '; observed ' + json.dumps(row.get('measurement', {}), sort_keys=True)
        parts.append('<li><a href="' + html.escape(link) + '">' + html.escape(text) + '</a></li>')
    parts.append('</ul>'); (run / 'report.html').write_text('\n'.join(parts))
    b.save(run / 'summary.json', dict(complete=ledger['complete'], groups=summaries))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--profile', choices=['smoke', 'full'], default='smoke')
    parser.add_argument('--host-state', choices=['quiet', 'busy'], required=True)
    parser.add_argument('--counts', type=int, nargs='+')
    parser.add_argument('--protocols', choices=['http1', 'https1'], nargs='+')
    parser.add_argument('--jdks', type=int, choices=[21, 25], nargs='+', default=[21, 25])
    parser.add_argument('--heap', choices=['256m', '384m'], default='256m',
                        help='Common maximum heap for every edition; total cgroup stays 512 MiB')
    args = parser.parse_args(); inputs = args.inputs.resolve()
    counts = args.counts if args.counts is not None else ([0, 64] if args.profile == 'smoke' else [0, 1024, 4096])
    protocols = args.protocols or (['https1'] if args.profile == 'smoke' else ['http1', 'https1'])
    if 0 not in counts or any(n < 0 or n > 4096 for n in counts) or len(set(counts)) != len(counts):
        parser.error('Require unique counts including zero, between 0 and 4096')
    if Path(args.run_id).name != args.run_id or args.run_id in ('.', '..'): parser.error('Run ID must be one directory name')
    try: descriptors = configure_descriptors(max(counts))
    except ValueError as error: parser.error(str(error))
    c = b.load_harness(inputs / 'harness'); manifest = b.verify_manifest(inputs, c)
    run = inputs / 'runs' / args.run_id; run.mkdir(parents=True, exist_ok=False)
    source = Path(__file__); (run / 'executed_connection_density.py').write_bytes(source.read_bytes())
    settings = dict(profile=args.profile, host_state=args.host_state, warmup=1 if args.profile == 'smoke' else 5,
                    duration=3 if args.profile == 'smoke' else 10, repeats=1 if args.profile == 'smoke' else 3,
                    counts=counts, protocols=protocols, jdks=args.jdks, cap_mib=512, heap=args.heap, target_rps=10000,
                    server_cpus='0-3,8-11', generator_cpus='4-7,12-15', controller_sha256=b.digest(source),
                    baseline_controller_sha256=b.digest(Path(b.__file__)), manifest_sha256=b.digest(inputs / 'manifest.json'),
                    client_descriptor_limits=descriptors)
    planned = plan(counts, protocols, args.jdks, settings['repeats'], settings['target_rps'])
    ledger = dict(spec=settings, plan=planned, trials=[], complete=False, started_epoch=time.time())
    b.save(run / 'results.json', ledger)
    b.save(run / 'hardware.json', dict(lscpu=subprocess.check_output(['lscpu', '-J'], text=True), before=b.host_snapshot()))
    c.SERVER_CPUS, c.GENERATOR_CPUS = settings['server_cpus'], settings['generator_cpus']
    os.sched_setaffinity(0, b.cpu_set(settings['generator_cpus'])); install_shutdown_observer(c)
    for item in planned:
        print('START', item['key'], flush=True)
        row = trial(c, inputs, run / 'trials' / item['key'], item, settings, Path(manifest['tool']['path']))
        ledger['trials'].append(row); b.save(run / 'results.json', ledger); report(run)
        print(json.dumps(dict(key=row['key'], outcome=row['outcome'], error=row.get('error'), exclusions=row['performance_exclusions'])), flush=True)
    b.verify_manifest(inputs, c)
    ledger.update(complete=True, final_identity_audit='pass', finished_epoch=time.time())
    b.save(run / 'results.json', ledger); report(run)
    print('REPORT', run / 'report.html', flush=True)


if __name__ == '__main__': main()
