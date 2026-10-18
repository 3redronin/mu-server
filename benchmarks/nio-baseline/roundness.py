#!/usr/bin/env python3
"""Short, rate-limited HTTP/2 regression probes against frozen three-way inputs."""
import argparse
import contextlib
import html
import os
from pathlib import Path
import shutil
import socket
import ssl
import statistics
import time

import baseline as b
import connection_density as d
from latency_tails import tail_percentiles


def cases(suite):
    if suite == 'fairness':
        return [(f'same-connection-paused-{count}', dict(b.case('h2streams'), clients=1, streams=1),
                 200, count, 16) for count in (0, 16)]
    bulk = dict(b.case('h2bulk'), clients=1, streams=8)
    upload = dict(bulk, kind='raw', compressibility='low')
    return [('download-window-65535', bulk, 32, 0, 16),
            ('download-window-1023', bulk, 32, 0, 10),
            ('upload-one-connection-eight-streams', upload, 32, 0, 16),
            ('upload-eight-connections-one-stream', dict(upload, clients=8, streams=1), 32, 0, 16)]


def plan(suite, jdks, repeats):
    result = []
    for repeat in range(repeats):
        for jdk in jdks:
            for index, (name, case, rate, paused, window) in enumerate(cases(suite)):
                offset = (repeat + index + (jdk == 25)) % 3
                for role in b.ROLES[offset:] + b.ROLES[:offset]:
                    result.append(dict(key=f'{repeat}/{jdk}/{name}/{role}', repeat=repeat, jdk=jdk,
                        role=role, workload=name, case=dict(case, id=name), target_rps=rate,
                        paused=0, stalled_streams=paused, window_bits=window, burst=suite == 'burst'))
    return result


def native_command(command, tool, window):
    """Change only the advertised stream receive window of the owned generator."""
    if not isinstance(command, (list, tuple)) or str(tool) not in command:
        return command
    result = list(command)
    if result.count('-w') != 1 or result[result.index('-w') + 1] != '16':
        raise AssertionError('Unexpected frozen receive-window argument')
    result[result.index('-w') + 1] = str(window)
    return result


def burst_command(command, directory, clients):
    """Eight total requests every 250 ms; timings continue past the run boundary."""
    result = list(command)
    index = result.index('--rps')
    del result[index:index+2]
    script = directory/'burst-timing.tsv'
    uri = result[-1]
    script.write_text(''.join(f'{100 + batch*250}\t{uri}\n'
                             for batch in range(32) for _ in range(8//clients)))
    result[-1:-1] = ['--timing-script-file', str(script)]
    return result


def overlap(path):
    """Observed concurrent request intervals, independently of configured -m."""
    events = []
    for line in path.read_text().splitlines():
        start, status, elapsed = map(int, line.split()[:3])
        if status != 200: continue
        events += [(start, 1), (start+elapsed, -1)]
    current = peak = 0
    for _, delta in sorted(events):
        current += delta
        peak = max(peak,current)
    return peak


def native_trial(c, inputs, directory, item, settings, tool):
    original = c.subprocess.Popen
    commands = []
    def popen(command, *args, **kwargs):
        command = native_command(command, tool, item['window_bits'])
        if isinstance(command, (list, tuple)) and str(tool) in command:
            if item.get('burst'): command = burst_command(command, directory, item['case']['clients'])
            commands.append(command)
        return original(command, *args, **kwargs)
    c.subprocess.Popen = popen
    try:
        row = d.native_with_cleanup(c, tool, lambda: b.run_trial(c, inputs, directory, item, settings, tool))
    finally:
        c.subprocess.Popen = original
    row['executed_generator_commands'] = commands
    raw_path = directory / 'h2load.json'
    if raw_path.exists() and 'measurement' in row:
        raw = b.read(raw_path)['measurements']
        row['tails'] = tail_percentiles(raw['performance']['request'], raw['requests']['done'])
    if (directory/'requests.tsv').exists():
        row['observed_peak_inflight'] = overlap(directory/'requests.tsv')
        if item.get('burst') and row['observed_peak_inflight'] < 4:
            row.update(outcome='insufficient request overlap',error='Burst failed to exercise at least four overlapping requests')
    return row


class SharedConnection:
    """Zero-credit streams and fully checked foreground bodies on ONE TLS socket.

    Only foreground streams receive stream credit. Connection credit is restored
    independently, so a zero stream window cannot masquerade as a connection stall.
    """
    def __init__(self, port, expected):
        from h2.config import H2Configuration
        from h2.connection import H2Connection
        from h2.settings import SettingCodes
        self.sock = None
        self.expected = expected
        self.streams = {}
        self.paused = []
        self.next_id = 1
        self.peer = H2Connection(config=H2Configuration(client_side=True, header_encoding='utf-8'))
        raw = socket.create_connection(('127.0.0.1', port), timeout=5)
        try:
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
            context.set_alpn_protocols(['h2'])
            self.sock = context.wrap_socket(raw, server_hostname='localhost')
            if self.sock.selected_alpn_protocol() != 'h2' or self.sock.cipher()[0] != 'TLS_AES_128_GCM_SHA256':
                raise AssertionError('Unexpected negotiated protocol/cipher')
            self.peer.initiate_connection()
            self.peer.update_settings({SettingCodes.INITIAL_WINDOW_SIZE: 0})
            self.peer.increment_flow_control_window(16 * 1024 * 1024 - 65535)
            self.flush()
        except BaseException:
            self.close(); raw.close(); raise

    def flush(self):
        data = self.peer.data_to_send()
        if data: self.sock.sendall(data)

    def start(self, size, credit):
        sid = self.next_id
        self.next_id += 2
        self.streams[sid] = dict(body=bytearray(), ended=False, headers=None, size=size, credit=credit)
        self.peer.send_headers(sid, [(':method', 'GET'), (':scheme', 'https'), (':authority', 'localhost'),
            (':path', f'/download?size={size}&compressibility=high'), ('accept-encoding', 'identity')], end_stream=True)
        if credit: self.peer.increment_flow_control_window(credit, stream_id=sid)
        self.flush()
        return sid

    def events(self, events):
        from h2.events import ResponseReceived, DataReceived, StreamEnded, StreamReset, ConnectionTerminated
        for event in events:
            if isinstance(event, (StreamReset, ConnectionTerminated)):
                raise AssertionError(f'Unexpected H2 termination: {event}')
            if isinstance(event, (ResponseReceived, DataReceived, StreamEnded)):
                state = self.streams[event.stream_id]
                if isinstance(event, ResponseReceived):
                    state['headers'] = dict(event.headers)
                    if state['headers'].get(':status') != '200':
                        raise AssertionError(f'Rejected stream: {state["headers"]}')
                elif isinstance(event, DataReceived):
                    state['body'].extend(event.data)
                    if len(state['body']) > state['credit']:
                        raise AssertionError('Server exceeded explicitly granted stream credit')
                    if event.flow_controlled_length:
                        self.peer.increment_flow_control_window(event.flow_controlled_length)
                else:
                    state['ended'] = True
                    if event.stream_id in self.paused:
                        raise AssertionError('Stalled stream ended before cancellation')

    def until(self, condition, timeout=5):
        deadline = time.monotonic() + timeout
        while not condition():
            remaining = deadline - time.monotonic()
            if remaining <= 0: raise TimeoutError('Shared-connection probe made no required progress')
            self.sock.settimeout(remaining)
            data = self.sock.recv(65536)
            if not data: raise EOFError('Shared H2 connection closed')
            self.events(self.peer.receive_data(data))
            self.flush()

    def stall(self, count):
        for _ in range(count):
            sid = self.start(b.PAUSED_BYTES, 0)
            self.paused.append(sid)
        self.until(lambda: all(self.streams[sid]['headers'] is not None for sid in self.paused))
        for sid in self.paused:
            state = self.streams[sid]
            b.check_paused_headers(state['headers'][':status'], state['headers'].get('content-length'), len(state['body']))

    def request(self):
        started = time.monotonic()
        sid = self.start(len(self.expected), len(self.expected))
        self.until(lambda: self.streams[sid]['ended'])
        elapsed = time.monotonic() - started
        state = self.streams.pop(sid)
        if state['headers'] is None or state['body'] != self.expected:
            raise AssertionError('Incorrect foreground payload')
        return elapsed

    def resume_and_cancel(self):
        for sid in self.paused:
            state = self.streams[sid]
            if state['ended'] or state['body']: raise AssertionError('Stalled stream progressed without credit')
            state['credit'] = 1
            self.peer.increment_flow_control_window(1, stream_id=sid)
        self.flush()
        self.until(lambda: all(len(self.streams[sid]['body']) == 1 for sid in self.paused))
        for sid in self.paused:
            if bytes(self.streams[sid]['body']) != self.expected[:1]:
                raise AssertionError('Wrong resumed payload')
            self.peer.reset_stream(sid)
        self.flush()
        return len(self.paused)

    def close(self):
        if self.sock:
            with contextlib.suppress(OSError): self.sock.close()


def paced_requests(peer, seconds, rate):
    """Single outstanding request, no catch-up burst; record dispatch lateness."""
    started = time.monotonic()
    deadline = started + seconds
    due = started
    records = []
    while due < deadline:
        now = time.monotonic()
        if now < due: time.sleep(due - now)
        sent = time.monotonic()
        if sent >= deadline: break
        elapsed = peer.request()
        records.append(dict(offset_seconds=sent-started, latency_seconds=elapsed,
                            dispatch_lateness_seconds=sent-due))
        due = max(due + 1/rate, time.monotonic())
    return dict(samples=records, elapsed_seconds=time.monotonic()-started)


def fairness_trial(c, inputs, directory, item, settings):
    row = dict(item, host_before=b.host_snapshot())
    fixture = peer = None
    try:
        mode = 'AUTO' if item['role'] == 'mu3' else ('PLATFORM' if item['jdk'] == 21 else 'VIRTUAL')
        fixture = c.Server(inputs, directory, item['role'], 'h2', jdk_version=item['jdk'],
                           java_options=[f'-Dcomparison.threading={mode}'])
        fixture.ready()
        with c.Wire(fixture.https, tls=True, alpn=['h2']) as wire:
            connection = c.H2(wire)
            connection.send(1, '/mode')
            response = connection.finish([1])[0]
            expected = b.expected_mode(item['role'], item['jdk'])
            if response['status'] != 200 or response['body_preview'] != expected:
                raise AssertionError('Worker/transport mismatch')
            fixture.record['mode_check'] = expected
        row['check_before'] = c.check(fixture, item['case'], inputs)
        peer = SharedConnection(fixture.https, c.payload(inputs, 128))
        peer.stall(item['stalled_streams'])
        row['shared_connection'] = dict(client_port=peer.sock.getsockname()[1], stalled=len(peer.paused),
            tls_cipher=peer.sock.cipher(), tls_version=peer.sock.version(), alpn=peer.sock.selected_alpn_protocol())
        fixture.phase = 'warmup'
        paced_requests(peer, settings['warmup'], item['target_rps'])
        fixture.phase = 'measurement'
        row['measurement_before'] = fixture.stats()
        row['foreground_connections'] = c.accepted_connections(fixture.pid, fixture.https)
        if row['foreground_connections'] != 1: raise AssertionError('Expected exactly one shared connection')
        samples = paced_requests(peer, settings['duration'], item['target_rps'])
        row['measurement_after'] = fixture.stats()
        fixture.phase = 'verification-after'
        b.save(directory/'probe-samples.json', samples)
        values = sorted(sample['latency_seconds'] for sample in samples['samples'])
        row['tails'] = tail_percentiles(dict(samples=values, max=values[-1]), len(values))
        before, after = row['measurement_before'], row['measurement_after']
        row['measurement'] = dict(requests=len(values), requests_per_second=len(values)/samples['elapsed_seconds'],
            p99_ms=row['tails']['p99_ms'], errors=0,
            cpu_us_per_request=(after['cpu_seconds']-before['cpu_seconds'])*1e6/len(values),
            gc_millis=after['gc_millis']-before['gc_millis'],
            max_dispatch_lateness_ms=max(sample['dispatch_lateness_seconds'] for sample in samples['samples'])*1000)
        row['resumed_streams'] = peer.resume_and_cancel()
        row['same_connection_recovery_ms'] = peer.request()*1000
        peer.close(); peer = None
        row['check_after'] = c.check(fixture, item['case'], inputs)
    except Exception as error:
        row['error'] = repr(error)
    finally:
        if peer: peer.close()
        if fixture:
            fixture.close()
            row['launch'] = fixture.record
            row['server_log'] = (directory/'server.log').read_text()
    row['outcome'] = c.classification(row)
    row['host_after'] = b.host_snapshot()
    return row


def finish_row(row):
    error = d.shutdown_error(row.get('launch', {}))
    if error and row['outcome'] == 'pass': row.update(outcome='cleanup failure', error=error)
    row['target_rate_met'] = row.get('measurement', {}).get('requests_per_second', 0) >= .98*row['target_rps']
    row['performance_exclusions'] = b.exclusions(row, 'busy', 'smoke')
    samples = row.get('launch', {}).get('samples', [])
    measured = [s for s in samples if s['phase'] == 'measurement']
    row['measurement_peak_mib'] = max([s['cgroup']['current'] for s in measured]+[0])/1048576
    row['native_threads'] = max([s.get('process', {}).get('os_threads', 0) for s in measured]+[0])
    return row


def report(run):
    ledger = b.read(run/'results.json')
    groups = []
    for item in ledger['plan']:
        key = (item['jdk'], item['workload'], item['role'])
        if any((g['jdk'], g['workload'], g['role']) == key for g in groups): continue
        rows = [r for r in ledger['trials'] if (r['jdk'], r['workload'], r['role']) == key]
        observed = [r for r in rows if 'measurement' in r]
        group = dict(jdk=key[0], workload=key[1], role=key[2], attempts=len(rows),
            correct=sum(r['outcome']=='pass' for r in rows), target_met=sum(r['target_rate_met'] for r in rows),
            valid_performance=0, trials=[r['key'] for r in rows])
        for name in ('requests_per_second', 'p99_ms', 'cpu_us_per_request', 'gc_millis'):
            values = [r['measurement'][name] for r in observed if r['measurement'].get(name) is not None]
            group[name] = statistics.median(values) if values else None
        for name in ('measurement_peak_mib', 'native_threads'):
            group[name] = statistics.median(r[name] for r in observed) if observed else None
        overlaps = [r['observed_peak_inflight'] for r in rows if 'observed_peak_inflight' in r]
        group['observed_peak_inflight_range'] = [min(overlaps),max(overlaps)] if overlaps else None
        groups.append(group)
    b.save(run/'summary.json', dict(complete=ledger['complete'], qualification='INDICATIVE ONLY: busy host, short rate-limited probes', groups=groups))
    esc = html.escape
    parts = ['<!doctype html><meta charset="utf-8"><title>HTTP/2 regression probes</title>',
        '<style>body{font:16px sans-serif;max-width:1400px;margin:30px}td,th{padding:8px;text-align:left}table{border-collapse:collapse}tr{border-bottom:1px solid #ccc}</style>',
        '<h1>HTTP/2 regression probes — INDICATIVE ONLY</h1>',
        '<p>PC in use; one second warm-up and three seconds measurement. Fixed offered load, not maximum throughput. '
        'Fresh JVMs; 512 MiB heaps, 1 GiB total caps and explicit 1 GiB direct ceilings. All timings excluded from controlled rankings. '
        'Fairness uses a Python client with one outstanding request and records dispatch lateness. Native probes use h2load. '
        'Do not compare latencies across the two client types. Short tails are exploratory.</p>',
        '<p>Native probes have approximately 96 measured responses per trial: their p99 is effectively the maximum. '
        'One second of low-rate warm-up does not establish steady-state JIT/GC behavior. The paced eight-stream setting '
        'is a concurrency ceiling; only the separate burst suite requires observed request overlap. '
        'Small stream windows exercise flow-control overhead on loopback, not realistic WAN delay.</p>',
        '<p>Burst timing scripts are relative to each connection. Separate connections can start batches at '
        'different offsets; their observed peak overlap is retained in summary.json. These are not globally synchronized batches.</p>',
        '<p><a href="results.json">Full ledger, settings and plan</a> · <a href="summary.json">Summary</a></p>',
        '<table><tr><th>Java</th><th>Workload</th><th>Edition</th><th>Correct / attempts</th><th>Target met</th><th>Observed /s</th><th>p99 ms</th><th>CPU µs/response</th><th>Measurement MiB</th><th>Native threads</th></tr>']
    def fmt(value): return '—' if value is None else f'{value:,.3f}'
    for g in groups:
        parts.append('<tr>'+''.join(f'<td>{esc(str(v))}</td>' for v in [g['jdk'],g['workload'],g['role'],
            f"{g['correct']}/{g['attempts']}",g['target_met'],*[fmt(g[k]) for k in
            ('requests_per_second','p99_ms','cpu_us_per_request','measurement_peak_mib','native_threads')]])+'</tr>')
    parts.append('</table><h2>All attempts</h2><ul>')
    for r in ledger['trials']:
        parts.append(f'<li><a href="trials/{esc(r["key"])}/result.json">{esc(r["key"])}</a>: {esc(r["outcome"])}; {esc(str(r.get("error", "")))}</li>')
    (run/'report.html').write_text(''.join(parts)+'</ul>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--suite', choices=('multiplex', 'burst', 'fairness'), required=True)
    parser.add_argument('--jdks', type=int, choices=(21,25), nargs='+', default=[21,25])
    parser.add_argument('--repeats', type=int, choices=(1,2,3), default=2)
    args = parser.parse_args()
    if Path(args.run_id).name != args.run_id: parser.error('run-id must be a single directory name')
    inputs = args.inputs.resolve()
    c = b.load_harness(inputs/'harness')
    manifest = b.verify_manifest(inputs, c)
    d.install_shutdown_observer(c)
    base = c.Server
    class DirectLimit(base):
        def __init__(self, *args, **kwargs):
            kwargs['java_options'] = [*kwargs.get('java_options', []), '-XX:MaxDirectMemorySize=1024m']
            super().__init__(*args, **kwargs)
    c.Server = DirectLimit
    run = inputs/'runs'/args.run_id
    run.mkdir(parents=True, exist_ok=False)
    sources = {}
    for name in ('roundness.py','baseline.py','connection_density.py','latency_tails.py'):
        source = Path(__file__).with_name(name)
        shutil.copyfile(source, run/('executed_'+name)); sources[name] = b.digest(source)
    settings = dict(profile='smoke',host_state='busy',warmup=1,duration=3,repeats=args.repeats,
        suite=args.suite,server_cpus='0-3,8-11',generator_cpus='4-7,12-15',sources=sources,
        manifest_sha256=b.digest(inputs/'manifest.json'),fixture_policy=manifest['fixture_policy'])
    c.SERVER_CPUS, c.GENERATOR_CPUS = settings['server_cpus'], settings['generator_cpus']
    os.sched_setaffinity(0, b.cpu_set(settings['generator_cpus']))
    ledger = dict(spec=settings,plan=plan(args.suite,args.jdks,args.repeats),trials=[],complete=False,started_epoch=time.time())
    b.save(run/'results.json',ledger)
    for item in ledger['plan']:
        print('START', item['key'], flush=True)
        directory = run/'trials'/item['key']
        if args.suite == 'fairness': row = fairness_trial(c, inputs, directory, item, settings)
        else: row = native_trial(c, inputs, directory, item, settings, Path(manifest['tool']['path']))
        finish_row(row)
        b.save(directory/'result.json',row)
        ledger['trials'].append(row); b.save(run/'results.json',ledger); report(run)
        print('RESULT',item['key'],row['outcome'],row.get('measurement',{}).get('requests_per_second'),row.get('error',''),flush=True)
    b.verify_manifest(inputs,c)
    ledger.update(complete=True,finished_epoch=time.time(),final_identity_audit='pass')
    b.save(run/'results.json',ledger); report(run)
    print('COMPLETE',run,flush=True)


if __name__ == '__main__': main()
