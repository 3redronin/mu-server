#!/usr/bin/env python3
"""Compare small-response latency with an active bulk stream on the same HTTP/2 connection.

The Python TLS client validates every completed body and reports dispatch lateness.
This is a contention/correctness probe, not a maximum-throughput load generator.
Each connection keeps one 1 MiB bulk request outstanding and paces 128-byte requests.
"""
import argparse
import hashlib
import math
import os
from pathlib import Path
import select
import socket
import ssl
import time
import baseline as b
import connection_density as d


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('before', 'after', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--jdks', default='21,25')
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--rate', type=int, default=500)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--duration', type=int, default=8)
    parser.add_argument('--nio-threads', type=int, default=1)
    parser.add_argument('--stream-window', type=int, default=2 * 1048576,
                        help='Initial per-stream flow-control credit in bytes')
    parser.add_argument('--server-cpus', default='0-3,8-11')
    parser.add_argument('--generator-cpus', default='4-7,12-15')
    args = parser.parse_args()
    for name in ('repeats', 'rate', 'warmup', 'duration', 'nio_threads'):
        if getattr(args, name) < 1:
            parser.error(name + ' must be positive')
    if not 1 <= args.stream_window <= 2147483647:
        parser.error('--stream-window must be between 1 and 2147483647 bytes')
    try:
        args.jdks = [int(jdk) for jdk in args.jdks.split(',')]
    except ValueError:
        parser.error('--jdks must contain 21 and/or 25')
    if not args.jdks or any(jdk not in (21, 25) for jdk in args.jdks):
        parser.error('--jdks must contain 21 and/or 25')
    return args


def pct(values, fraction):
    return sorted(values)[math.ceil(len(values) * fraction) - 1]


def trial(c, inp, directory, jdk, edition, repeat, a, role='nio'):
    from h2.config import H2Configuration
    from h2.connection import H2Connection
    from h2.events import ResponseReceived, DataReceived, StreamEnded, StreamReset, ConnectionTerminated
    from h2.settings import SettingCodes
    row = dict(jdk=jdk, edition=edition, repeat=repeat, evidence_directory=str(directory), host_before=b.host_snapshot())
    server = sock = None
    try:
        mode = 'AUTO' if role == 'mu3' else 'PLATFORM' if jdk == 21 else 'VIRTUAL'
        options = [f'-Dcomparison.threading={mode}']
        if role == 'nio': options.append(f'-Dcomparison.nioThreads={a.nio_threads}')
        server = c.Server(inp, directory, role, 'h2', jdk_version=jdk, java_options=options)
        server.ready()
        port = server.record['ready']['port']
        row['check_before'] = c.check(server, b.case('h2bulk'), inp)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.minimum_version = ctx.maximum_version = ssl.TLSVersion.TLSv1_3
        ctx.set_alpn_protocols(['h2'])
        sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        sock = ctx.wrap_socket(sock, server_hostname='localhost')
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(5)
        assert sock.selected_alpn_protocol() == 'h2' and sock.cipher()[0] == 'TLS_AES_128_GCM_SHA256'
        h = H2Connection(config=H2Configuration(client_side=True, header_encoding='utf-8'))
        h.initiate_connection()
        h.update_settings({SettingCodes.INITIAL_WINDOW_SIZE: a.stream_window})
        h.increment_flow_control_window(16 * 1048576 - 65535)
        active = {}
        next_id = 1
        bulk = None
        results = []
        bulk_results = []
        expected = {size: hashlib.sha256(c.payload(inp, size)).hexdigest() for size in [128, 1048576]}

        def submit(kind, due=None, index=None):
            nonlocal next_id, bulk
            sid = next_id
            next_id += 2
            size = 1048576 if kind == 'bulk' else 128
            path = '/mode' if kind == 'mode' else f'/download?size={size}&compressibility=high'
            h.send_headers(sid, [(':method', 'GET'), (':scheme', 'https'), (':authority', 'localhost'),
                                 (':path', path), ('accept-encoding', 'identity')], end_stream=True)
            active[sid] = dict(kind=kind, due=due, index=index, sent=time.monotonic(), size=size,
                               length=0, sha=hashlib.sha256(), status=None, body=bytearray())
            if kind == 'bulk':
                bulk = sid

        def flush():
            data = h.data_to_send()
            if data:
                sock.sendall(data)

        def receive():
            nonlocal bulk
            data = sock.recv(65536)
            assert data, 'Unexpected EOF'
            for event in h.receive_data(data):
                if isinstance(event, (StreamReset, ConnectionTerminated)):
                    raise AssertionError(str(event))
                if isinstance(event, ResponseReceived):
                    active[event.stream_id]['status'] = dict(event.headers).get(':status')
                elif isinstance(event, DataReceived):
                    state = active[event.stream_id]
                    state['length'] += len(event.data)
                    state['sha'].update(event.data)
                    if state['kind'] == 'mode':
                        state['body'].extend(event.data)
                    else:
                        assert state['length'] <= state['size']
                    h.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
                elif isinstance(event, StreamEnded):
                    state = active.pop(event.stream_id)
                    end = time.monotonic()
                    assert state['status'] == '200'
                    if state['kind'] == 'mode':
                        assert state['body'].decode() == b.expected_mode(role, jdk)
                        row['mode_check'] = state['body'].decode()
                        server.record['mode_check'] = row['mode_check']
                        continue
                    assert state['length'] == state['size'] and state['sha'].hexdigest() == expected[state['size']]
                    if state['kind'] == 'bulk':
                        bulk = None
                        bulk_results.append(dict(sent=state['sent'], end=end))
                    else:
                        results.append(dict(index=state['index'], due=state['due'], sent=state['sent'], end=end,
                                            latency_ms=1000 * (end - state['sent']),
                                            scheduled_ms=1000 * (end - state['due']),
                                            late_ms=1000 * (state['sent'] - state['due'])))
        submit('mode')
        flush()
        while active:
            receive()
            flush()
        start = time.monotonic()
        measure = start + a.warmup
        finish = measure + a.duration
        row['timing'] = dict(start=start, measurement_start=measure, measurement_end=finish)
        total = (a.warmup + a.duration) * a.rate
        next_small = 0
        started = False
        peak = 0
        while True:
            now = time.monotonic()
            assert now < finish + 15, 'Foreground failed to drain'
            if not started and now >= measure:
                server.phase = 'measurement'
                row['before'] = server.stats()
                row['connections_before'] = c.accepted_connections(server.pid, port)
                assert row['connections_before'] == 1
                started = True
            # Bound client backlog and submission work; expose pacing misses in the samples.
            for _ in range(16):
                if next_small >= total or now < start + next_small / a.rate or len(active) >= 129:
                    break
                submit('small', start + next_small / a.rate, next_small)
                next_small += 1
            peak = max(peak, sum(x['kind'] == 'small' for x in active.values()))
            if now < finish and bulk is None and getattr(a, 'background', True):
                submit('bulk')
            flush()
            if next_small == total and not any(x['kind'] == 'small' for x in active.values()):
                break
            due = start + next_small / a.rate if next_small < total else finish + 15
            delay = min(0.01, max(0, due - time.monotonic()))
            if sock.pending() or select.select([sock], [], [], delay)[0]:
                receive()
        elapsed = max(a.duration, time.monotonic() - measure)
        row['after'] = server.stats()
        row['connections_after'] = c.accepted_connections(server.pid, port)
        assert row['connections_after'] == 1
        if bulk is not None:
            # Only completed bulk responses enter the body-validated counts. Explicitly
            # cancel the one remaining bulk stream before the recovery probe.
            h.reset_stream(bulk)
            flush()
        observations = [r for r in results if r['index'] >= a.warmup * a.rate]
        assert len(observations) == a.duration * a.rate
        bins = [sum(measure + i <= r['end'] < measure + i + 1 for r in bulk_results)
                for i in range(a.duration)]
        if getattr(a, 'background', True): assert all(n > 0 for n in bins), bins
        row['measurement'] = dict(
            requests=len(observations), elapsed_seconds=elapsed, requests_per_second=len(observations) / elapsed,
            p50_ms=pct([r['latency_ms'] for r in observations], 0.5),
            p99_ms=pct([r['latency_ms'] for r in observations], 0.99),
            scheduled_p99_ms=pct([r['scheduled_ms'] for r in observations], 0.99),
            dispatch_p99_ms=pct([r['late_ms'] for r in observations], 0.99),
            bulk_per_second=bins, peak_small_inflight=peak)
        b.save(directory / 'foreground.json', results)
        b.save(directory / 'bulk.json', bulk_results)
        sock.close()
        sock = None
        server.phase = 'recovery'
        row['check_after'] = c.check(server, b.case('h2bulk'), inp)
        row['outcome'] = 'pass'
    except Exception as error:
        row.update(outcome='failure', error=repr(error))
    finally:
        if sock:
            sock.close()
        if server:
            server.close()
            row['launch'] = server.record
    cleanup_error = d.shutdown_error(row.get('launch', {}))
    if cleanup_error:
        row.update(outcome='cleanup failure', error=cleanup_error)
    row['host_after'] = b.host_snapshot()
    b.save(directory / 'result.json', row)
    return row


def main():
    args = options()
    inputs = {'baseline': args.before.resolve(), 'candidate': args.after.resolve()}
    out = args.out.resolve()
    c = b.load_harness(inputs['baseline'] / 'harness')
    manifests = {edition: b.verify_manifest(path, c) for edition, path in inputs.items()}
    assert len({m['files']['inputs/PerformanceFixture.java'] for m in manifests.values()}) == 1
    assert all('bounded async' in m['fixture_policy'].lower() for m in manifests.values())
    c.SERVER_CPUS, c.GENERATOR_CPUS = args.server_cpus, args.generator_cpus
    os.sched_setaffinity(0, b.cpu_set(c.GENERATOR_CPUS))
    d.install_shutdown_observer(c)
    out.mkdir(parents=True, exist_ok=False)
    (out / 'executed.py').write_text(Path(__file__).read_text())
    settings = {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}
    settings['controller_sources'] = {str(Path(p).resolve()): b.digest(Path(p))
                                      for p in (__file__, b.__file__, d.__file__)}
    settings['inputs'] = {k: dict(path=str(v), manifest_sha256=b.digest(v / 'manifest.json')) for k, v in inputs.items()}
    ledger = dict(settings=settings, trials=[], complete=False)
    b.save(out / 'results.json', ledger)
    for repeat in range(args.repeats):
        for jdk in args.jdks:
            for edition in ['baseline', 'candidate'] if repeat % 2 == 0 else ['candidate', 'baseline']:
                print('START', repeat, jdk, edition, flush=True)
                row = trial(c, inputs[edition], out / 'trials' / str(repeat) / str(jdk) / edition,
                            jdk, edition, repeat, args)
                ledger['trials'].append(row)
                b.save(out / 'results.json', ledger)
                print('RESULT', row['outcome'], row.get('measurement'), row.get('error'), flush=True)
                if row['outcome'] != 'pass':
                    raise RuntimeError('Failed trial; see retained evidence in ' + row['evidence_directory'])
    for path in inputs.values():
        b.verify_manifest(path, c)
    for path, digest in settings['controller_sources'].items():
        assert b.digest(Path(path)) == digest, 'Controller changed during measurement'
    ledger.update(complete=True, final_identity_audit='pass',
                  qualification='Same-connection active-writer fairness; every completed body validated; '
                                'inspect Python dispatch lateness before attributing latency')
    b.save(out / 'results.json', ledger)


if __name__ == '__main__':
    main()
