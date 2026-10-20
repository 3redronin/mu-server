#!/usr/bin/env python3
"""Measure paced HTTP/1 latency while bounded async downloads share NIO transport owners.

Compare two frozen NIO builds with identical fixtures. The foreground validates every
128-byte body and records dispatch lateness. Eight native clients continuously drain
one outstanding 1 MiB response each. This is separate from saturated throughput and
paused-reader isolation: writable bulk connections compete for the same owner.
"""
import argparse
import hashlib
import math
import os
from pathlib import Path
import signal
import subprocess
import time

import baseline as b
import connection_density as d


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True, help='Frozen bounded-async baseline inputs')
    parser.add_argument('--after', type=Path, required=True, help='Frozen bounded-async candidate inputs')
    parser.add_argument('--out', type=Path, required=True, help='New evidence directory')
    parser.add_argument('--jdks', default='21,25')
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--duration', type=int, default=5)
    parser.add_argument('--background-clients', type=int, default=8)
    parser.add_argument('--foreground-rps', type=int, default=1000)
    parser.add_argument('--nio-threads', type=int, default=1)
    parser.add_argument('--server-cpus', default='0-3,8-11')
    parser.add_argument('--generator-cpus', default='4-7,12-15')
    args = parser.parse_args()
    for name in ('repeats', 'warmup', 'duration', 'background_clients', 'foreground_rps', 'nio_threads'):
        if getattr(args, name) < 1:
            parser.error(name + ' must be positive')
    try:
        args.jdks = [int(jdk) for jdk in args.jdks.split(',')]
    except ValueError:
        parser.error('--jdks must contain only 21 and/or 25')
    if not args.jdks or any(jdk not in (21, 25) for jdk in args.jdks):
        parser.error('--jdks must contain only 21 and/or 25')
    return args


def paced(c, wire, expected, seconds, rate):
    samples = []
    start, epoch = time.monotonic(), time.time()
    for index in range(rate * seconds):
        due = start + index / rate
        delay = due - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        sent = time.monotonic()
        wire.send(c.request('/download?size=128&compressibility=high'))
        reply = wire.response()
        end = time.monotonic()
        assert reply['status'] == 200 and reply['body_size'] == 128 and reply['body_sha256'] == expected
        samples.append(dict(latency_ms=(end - sent) * 1000,
                            scheduled_latency_ms=(end - due) * 1000,
                            dispatch_lateness_ms=(sent - due) * 1000))
    return dict(samples=samples, elapsed=time.monotonic() - start, epoch=epoch)


def percentile(samples, key, fraction):
    values = sorted(sample[key] for sample in samples)
    return values[math.ceil(len(values) * fraction) - 1]


def trial(c, tool, inputs, directory, jdk, edition, repeat, args):
    row = dict(jdk=jdk, edition=edition, repeat=repeat,
               evidence_directory=str(directory), host_before=b.host_snapshot())
    fixture = generator = log = None
    try:
        mode = 'PLATFORM' if jdk == 21 else 'VIRTUAL'
        fixture = c.Server(inputs, directory, 'nio', 'http1', jdk_version=jdk,
                           java_options=[f'-Dcomparison.threading={mode}',
                                         f'-Dcomparison.nioThreads={args.nio_threads}'])
        fixture.ready()
        port = fixture.record['ready']['port']
        with c.Wire(port) as wire:
            wire.send(c.request('/mode'))
            reply = wire.response()
            assert reply['status'] == 200 and reply['body_preview'] == b.expected_mode('nio', jdk)
        fixture.record['mode_check'] = reply['body_preview']
        row['bulk_probe_before'] = c.check(fixture, b.case('h1bulk'), inputs)
        command = [
            'taskset', '-c', c.GENERATOR_CPUS, str(tool),
            '-c', str(args.background_clients), '-t', str(min(4, args.background_clients)),
            '-m', '1', '-D', f'{args.warmup + args.duration + 6}s', '--warm-up-time', '1s',
            '-N', '5s', '--h1', '--output-file', str(directory / 'background.json'),
            '--log-file', str(directory / 'background.tsv'),
            f'http://127.0.0.1:{port}/download?size=1048576&compressibility=high']
        row['background_command'] = command
        log = (directory / 'background.log').open('w')
        generator = c.subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=True)
        deadline = time.monotonic() + 3
        while c.accepted_connections(fixture.pid, port) != args.background_clients:
            assert generator.poll() is None and time.monotonic() < deadline
            time.sleep(.01)
        expected = hashlib.sha256(c.payload(inputs, 128)).hexdigest()
        with c.Wire(port, timeout=args.warmup + args.duration + 15) as wire:
            fixture.phase = 'warmup'
            paced(c, wire, expected, args.warmup, args.foreground_rps)
            fixture.phase = 'measurement'
            row['measurement_before'] = fixture.stats()
            assert generator.poll() is None
            row['accepted_connections'] = c.accepted_connections(fixture.pid, port)
            assert row['accepted_connections'] == args.background_clients + 1
            samples = paced(c, wire, expected, args.duration, args.foreground_rps)
            row['measurement_after'] = fixture.stats()
            assert generator.poll() is None
            row['connections_after_measurement'] = c.accepted_connections(fixture.pid, port)
            assert row['connections_after_measurement'] == args.background_clients + 1
            b.save(directory / 'foreground-samples.json', samples)
        assert generator.wait(timeout=args.warmup + args.duration + 15) == 0
        raw = b.read(directory / 'background.json')['measurements']
        audit = c.audit_requests(directory / 'background.tsv')
        background_case = dict(b.case('h1bulk'), clients=args.background_clients)
        row['background_warmup_carryover'] = c.reconcile_native(audit, raw['requests'], background_case)
        assert raw['requests']['failed'] == raw['requests']['errored'] == raw['requests']['timeout'] == 0
        start = samples['epoch'] * 1e6
        end = start + samples['elapsed'] * 1e6
        active = 0
        bins = [0] * math.ceil(samples['elapsed'])
        for line in (directory / 'background.tsv').read_text().splitlines():
            timestamp, status, elapsed = map(int, line.split()[:3])
            completed = timestamp + elapsed
            if status == 200 and start <= completed < end:
                active += 1
                bins[int((completed - start) / 1e6)] += 1
        # Every full second must contain bulk completions; a live but stalled generator
        # cannot qualify as competition from continuously draining writers.
        assert all(count > args.background_clients for count in bins[:math.floor(samples['elapsed'])]), bins
        row.update(background_audit=audit, background_during_foreground=active,
                   background_completions_per_second=bins, background_exit_code=generator.returncode)
        observations = samples['samples']
        row['measurement'] = dict(
            errors=0, requests=len(observations), requests_per_second=len(observations) / samples['elapsed'],
            p50_ms=percentile(observations, 'latency_ms', .5), p99_ms=percentile(observations, 'latency_ms', .99),
            scheduled_p99_ms=percentile(observations, 'scheduled_latency_ms', .99),
            dispatch_p99_ms=percentile(observations, 'dispatch_lateness_ms', .99),
            max_ms=max(sample['latency_ms'] for sample in observations))
        fixture.phase = 'recovery'
        row['bulk_probe_after'] = c.check(fixture, b.case('h1bulk'), inputs)
        row['recovered'] = fixture.stats()
        assert row['measurement']['requests_per_second'] > args.foreground_rps * .98
        row['outcome'] = 'pass'
    except Exception as error:
        row.update(outcome='failure', error=repr(error))
    finally:
        try:
            if generator is not None and generator.poll() is None:
                row['background_forced'] = True
                try:
                    os.killpg(generator.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    generator.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(generator.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    generator.wait(timeout=5)
        finally:
            if log:
                log.close()
            if fixture:
                fixture.close()
                row['launch'] = fixture.record
    row['host_after'] = b.host_snapshot()
    return row


def main():
    args = options()
    before, after, out = args.before.resolve(), args.after.resolve(), args.out.resolve()
    c = b.load_harness(before / 'harness')
    c.SERVER_CPUS, c.GENERATOR_CPUS = args.server_cpus, args.generator_cpus
    os.sched_setaffinity(0, b.cpu_set(c.GENERATOR_CPUS))
    d.install_shutdown_observer(c)
    manifests = {key: b.verify_manifest(path, c) for key, path in [('before', before), ('after', after)]}
    assert manifests['before']['files']['inputs/PerformanceFixture.java'] == manifests['after']['files']['inputs/PerformanceFixture.java'], 'Fixtures differ'
    assert manifests['before']['tool'] == manifests['after']['tool'], 'Native generator identities differ'
    assert all('bounded async' in value['fixture_policy'].lower() for value in manifests.values()), 'Bounded async fixtures required'
    tool = Path(manifests['before']['tool']['path'])
    out.mkdir(parents=True, exist_ok=False)
    (out / 'executed.py').write_text(Path(__file__).read_text())
    settings = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()}
    settings['manifest_sha256'] = {str(path): b.digest(path / 'manifest.json') for path in (before, after)}
    settings['controller_sources'] = {str(Path(path).resolve()): b.digest(Path(path))
                                      for path in (__file__, b.__file__, d.__file__)}
    b.save(out / 'settings.json', settings)
    rows = []
    for repeat in range(args.repeats):
        for jdk in args.jdks:
            for edition in (('before', 'after') if repeat % 2 == 0 else ('after', 'before')):
                inputs = before if edition == 'before' else after
                directory = out / 'trials' / str(repeat) / str(jdk) / edition
                print('START', repeat, jdk, edition, flush=True)
                row = d.native_with_cleanup(c, tool, lambda: trial(
                    c, tool, inputs, directory, jdk, edition, repeat, args))
                cleanup_error = d.shutdown_error(row.get('launch', {}))
                if cleanup_error:
                    row.update(outcome='cleanup failure', error=cleanup_error)
                b.save(directory / 'result.json', row)
                rows.append(row)
                b.save(out / 'results.json', dict(settings=settings, trials=rows, complete=False))
                print('RESULT', row['outcome'], row.get('measurement'), row.get('error'), flush=True)
                if row['outcome'] != 'pass':
                    raise RuntimeError('Fairness trial failed; raw evidence retained in ' + str(directory))
    for path in (before, after):
        b.verify_manifest(path, c)
    for path, digest in settings['controller_sources'].items():
        assert b.digest(Path(path)) == digest, 'Controller changed during measurement'
    b.save(out / 'results.json', dict(
        settings=settings, trials=rows, complete=True, final_identity_audit='pass',
        qualification='Indicative bounded-owner load test; paced Python foreground validates every body and records dispatch lateness; native background drained throughout foreground'))


if __name__ == '__main__':
    main()
