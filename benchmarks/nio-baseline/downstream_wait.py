#!/usr/bin/env python3
"""Source-pinned downstream-wait comparison, with paced independent foreground requests.

Short runs are harness validation and indicative observations, never a full baseline.
The dependency is a separate timer-based process with no artificial pool limit.
"""
import argparse
import hashlib
import html
import importlib.metadata
import json
import math
import os
from pathlib import Path
import queue
import shutil
import signal
import socket
import ssl
import statistics
import subprocess
import sys
import threading
import time

import baseline as b
import connection_density as d
from downstream_load import shape

HERE = Path(__file__).resolve().parent


def prepare(source, out):
    source, out = source.resolve(), out.resolve()
    c = b.load_harness(source / 'harness')
    original = b.verify_manifest(source, c)
    out.mkdir(parents=True, exist_ok=False)
    for name in ('harness', 'inputs'):
        shutil.copytree(source / name, out / name, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copy2(HERE / 'downstream/PerformanceFixture.java', out / 'inputs/PerformanceFixture.java')
    provenance = {}
    for role in b.ROLES:
        c.process.TARGET = source / 'frozen'
        metadata = c.process.load_build(role)
        destination = out / 'frozen/build' / role
        destination.mkdir(parents=True)
        # Preserve compiled production inputs, not the potentially changed checkout.
        paths = [Path(p) for p in metadata['classpath'].split(os.pathsep) if not p.endswith('.jar')]
        production = [p for p in paths if not (p / 'validation/PerformanceFixture.class').exists()]
        if len(production) != 1:
            raise ValueError('Expected exactly one production classes directory')
        shutil.copytree(production[0], destination / 'production-classes')
        classes = destination / 'performance-classes'
        classes.mkdir()
        cp = os.pathsep.join(map(str, [classes, destination / 'production-classes', *metadata['dependencies']]))
        fixture = out / 'inputs/PerformanceFixture.java'
        c.process.run_logged([c.process.jdk_path(21) / 'bin/javac', '--release', '11', '-cp', cp,
                              '-d', classes, fixture], out / (role + '-javac.log'))
        metadata.update(classpath=cp, fixture_sources={str(fixture): b.digest(fixture)},
                        class_outputs={str(p): c.process.output_inventory(p)
                                       for p in (classes, destination / 'production-classes')})
        b.save(destination / 'build.json', metadata)
        provenance[role] = metadata['source']
    files = {str(p.relative_to(out)): b.digest(p) for base in (out / 'harness', out / 'inputs')
             for p in sorted(base.rglob('*')) if p.is_file() and '__pycache__' not in p.parts}
    for role in b.ROLES:
        filename = out / 'frozen/build' / role / 'build.json'
        files[str(filename.relative_to(out))] = b.digest(filename)
    b.save(out / 'manifest.json', dict(schema_version=1, files=files, provenance=provenance,
        tool=original['tool'], parent_manifest=dict(path=str(source / 'manifest.json'), sha256=b.digest(source / 'manifest.json')),
        checkout_identity=c.process.identity(HERE.parents[1]),
        fixture_policy='Identical downstream fixture; shared JDK client; one bounded async response write in both handler styles'))
    b.verify_manifest(out, c)
    print('PREPARED', out, flush=True)


class Child:
    """One owned process group, bounded pipe reads, retained log, and unconditional reaping."""
    def __init__(self, command, log):
        self.command = list(map(str, command))
        self.lines = queue.Queue()
        self.record = dict(command=self.command)
        self.log = Path(log).open('w')
        try:
            self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
        except BaseException:
            self.log.close()
            raise
        self.record['pid'] = self.process.pid
        self.lock = threading.Lock()

        def read():
            for line in self.process.stdout:
                self.log.write(line); self.log.flush(); self.lines.put(line.rstrip())
            self.lines.put(None)
        self.reader = threading.Thread(target=read, name='downstream-child-log', daemon=True)
        self.reader.start()

    def line(self, prefix, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = self.lines.get(timeout=max(.001, deadline - time.monotonic()))
            except queue.Empty:
                break
            if value is None:
                raise RuntimeError('Child exited before ' + prefix)
            if prefix in value:
                return value
        raise TimeoutError('Child did not emit ' + prefix)

    def control(self, command, prefix):
        with self.lock:
            self.process.stdin.write(command + '\n'); self.process.stdin.flush()
            return self.line(prefix, 5)

    def close(self, graceful=False):
        if graceful and self.process.poll() is None:
            try:
                self.control('STOP', 'STOPPED')
                self.process.wait(timeout=5)
            except (OSError, RuntimeError, TimeoutError, subprocess.TimeoutExpired) as error:
                self.record['stop_error'] = repr(error)
        forced = self.process.poll() is None
        if forced:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=3)
        else:
            self.process.wait(timeout=3)
        self.reader.join(timeout=3)
        self.record.update(forced=forced, exit_code=self.process.returncode, reader_alive=self.reader.is_alive(),
                           reaped_epoch=time.time())
        if self.reader.is_alive():
            raise RuntimeError('Child log reader did not stop')
        self.process.stdin.close(); self.process.stdout.close(); self.log.close()


def backend(inputs, directory, cpus):
    child = Child(['taskset', '-c', cpus, sys.executable, HERE / 'downstream_service.py',
                   '--payload', inputs / 'inputs/high.bin', '--output', directory / 'downstream.json'],
                  directory / 'downstream.log')
    return child


def backend_stats(child):
    return json.loads(child.control('STATS', 'STATS ').split('STATS ', 1)[1])


def metrics(fixture):
    return dict(json.loads(fixture.control('METRICS', 'METRICS ').split('METRICS ', 1)[1]), epoch=time.time())


class Probe:
    def __init__(self, c, port, protocol):
        self.c = c
        self.wire = c.Wire(port, tls=protocol != 'http1', alpn=['h2' if protocol == 'h2' else 'http/1.1'], timeout=5)
        self.peer = None
        self.sid = 1
        try:
            if protocol == 'h2':
                if self.wire.tls['alpn'] != 'h2':
                    raise AssertionError('Foreground did not negotiate HTTP/2')
                self.peer = c.H2(self.wire)
        except BaseException:
            self.wire.close()
            raise

    def send(self, path):
        self.wire.deadline = time.monotonic() + 5
        if self.peer:
            self.peer.send(self.sid, path)
        else:
            self.wire.send(self.c.request(path))

    def receive(self):
        if self.peer:
            response = self.peer.finish([self.sid])[0]
            del self.peer.streams[self.sid]
            self.peer.frames.clear()
            self.sid += 2
            return response
        return self.wire.response()

    def request(self, path):
        self.send(path)
        return self.receive()

    def close(self):
        self.wire.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def require_body(reply, expected, status=200):
    if reply['status'] != status or reply['body_size'] != 128 or reply['body_sha256'] != expected:
        raise AssertionError('Incorrect fixture response: ' + repr(reply))


def paced(probe, expected, seconds, rate):
    """Skip overdue slots rather than turn a delayed foreground into a catch-up burst."""
    start, epoch = time.monotonic(), time.time()
    samples, skipped, index = [], 0, 0
    count = int(seconds * rate)
    while index < count:
        now = time.monotonic()
        current_slot = min(count, int((now - start) * rate))
        if current_slot > index:
            skipped += current_slot - index
            index = current_slot
        if index >= count:
            break
        due = start + index / rate
        if due > now:
            time.sleep(due - now)
        sent = time.monotonic()
        require_body(probe.request('/local'), expected)
        end = time.monotonic()
        samples.append(dict(slot=index, latency_ms=(end - sent) * 1000,
                            scheduled_latency_ms=(end - due) * 1000, dispatch_lateness_ms=(sent - due) * 1000))
        index += 1
    remaining = start + seconds - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)
    return dict(epoch=epoch, elapsed=time.monotonic() - start, offered=count, missed=skipped, samples=samples)


def percentile(values, fraction=.99):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)] if values else None


def plan(jdks=(21, 25), repeats=1, delays=(0, 250), protocols=('http1', 'h2'), roles=b.ROLES):
    rows = []
    cases = [(protocol, style, delay) for protocol in protocols
             for style, delay in [('direct', 0)] + [(style, delay) for delay in delays
                 if protocol == 'http1' or delay != 0 for style in ('blocking', 'async')]]
    for repeat in range(repeats):
        for jdk in jdks:
            for index, (protocol, style, delay) in enumerate(cases):
                offset = (repeat + index + (jdk == 25)) % 3
                for role in b.ROLES[offset:] + b.ROLES[:offset]:
                    if role in roles:
                        key = f'{repeat}/{jdk}/{protocol}-{style}-{delay}/{role}'
                        rows.append(dict(key=key, jdk=jdk, repeat=repeat, protocol=protocol, style=style, delay_ms=delay, role=role))
    return rows


def load_command(c, inputs, directory, port, protocol, delay, rate, warmup, duration, calibration=False):
    path = f'/download?delay={delay}' if calibration else '/download?size=128&compressibility=high'
    return ['taskset', '-c', c.GENERATOR_CPUS, sys.executable, str(HERE / 'downstream_load.py'),
            '--port', str(port), '--protocol', protocol, '--path', path, '--rate', str(rate),
            '--warmup', str(warmup), '--duration', str(duration),
            '--payload', str(inputs / 'inputs/high.bin'), '--output', str(directory / 'background.json')]


def finish_cleanup(row, fixture, generator, dependency):
    """Attempt every independent cleanup even if an earlier cleanup fails."""
    errors = []
    for name, child in [('generator', generator), ('fixture', fixture), ('dependency', dependency)]:
        if child is None:
            continue
        try:
            if name == 'fixture':
                child.close()
                error = d.shutdown_error(child.record)
                if error:
                    errors.append(error)
            else:
                child.close(graceful=name == 'dependency')
                if child.record.get('forced') or child.record.get('exit_code') != 0 or child.record.get('stop_error'):
                    errors.append(name + ' did not exit normally')
        except Exception as error:
            errors.append(name + ': ' + repr(error))
        finally:
            row['launch' if name == 'fixture' else name + '_lifecycle'] = child.record
    if errors:
        row['cleanup_errors'] = errors
        if row.get('outcome') == 'pass':
            row.update(outcome='cleanup failure', error='; '.join(errors))


def fixture_options(item, url):
    mode = 'AUTO' if item['role'] == 'mu3' else 'PLATFORM' if item['jdk'] == 21 else 'VIRTUAL'
    return [f'-Dcomparison.threading={mode}', f'-Ddownstream.style={item["style"]}', f'-Ddownstream.url={url}']


def trial(c, inputs, directory, item, args, inject=None):
    row = dict(item, host_before=b.host_snapshot(), outcome='failure', evidence_directory=str(directory))
    fixture = generator = dependency = None
    telemetry, telemetry_errors = [], []
    stop = threading.Event()
    sampler = None
    directory.parent.mkdir(parents=True, exist_ok=True)
    # Server creates its own directory; dependency logs live in a separate owned directory.
    support = directory.with_name(directory.name + '-dependency')
    support.mkdir()
    try:
        dependency = backend(inputs, support, args.downstream_cpus)
        backend_port = int(dependency.line('READY ').split()[2])
        url = f'http://127.0.0.1:{backend_port}/download?delay={item["delay_ms"]}'
        fixture = c.Server(inputs, directory, item['role'], item['protocol'], jdk_version=item['jdk'],
                           java_options=fixture_options(item, url))
        if inject == 'startup':
            raise RuntimeError('injected startup failure')
        fixture.ready()
        port = fixture.record['ready']['port']
        expected = hashlib.sha256(c.payload(inputs, 128)).hexdigest()
        with Probe(c, port, item['protocol']) as probe:
            mode = probe.request('/mode')['body_preview']
            if mode != b.expected_mode(item['role'], item['jdk']):
                raise AssertionError('Unexpected worker policy: ' + mode)
            row['mode_check'] = mode
            require_body(probe.request('/download'), expected)
            require_body(probe.request('/local'), expected)
        command = load_command(c, inputs, directory, port, item['protocol'], 0, args.background_rps,
                               args.warmup, args.duration + 1)
        fixture.phase = 'warmup'
        generator = Child(command, directory / 'background.log')
        generator.line('READY ')
        if inject == 'measurement':
            raise RuntimeError('injected measurement failure')
        with Probe(c, port, item['protocol']) as probe:
            generator.control('START', 'STARTED')
            b.save(directory / 'foreground-warmup.json', paced(probe, expected, args.warmup, args.foreground_rps))
            generator.line('MEASUREMENT', 10)
            row['connections_at_measurement'] = c.accepted_connections(fixture.pid, port)
            fixture.control('RESET_PEAKS', 'RESET_PEAKS')
            row['measurement_before'] = fixture.stats()
            row['downstream_before'] = backend_stats(dependency)
            row['metrics_before'] = metrics(fixture)
            row['generator_before'] = c.proc_metrics(generator.process.pid)
            row['generator_threads_before'] = c.thread_cpu(generator.process.pid)
            fixture.phase = 'measurement'

            def sample():
                while not stop.is_set():
                    try:
                        telemetry.append(metrics(fixture))
                    except Exception as error:
                        telemetry_errors.append(repr(error))
                        return
                    stop.wait(.25)
            sampler = threading.Thread(target=sample, name='downstream-metrics')
            sampler.start()
            foreground = paced(probe, expected, args.duration, args.foreground_rps)
            stop.set(); sampler.join(timeout=6)
            if sampler.is_alive() or telemetry_errors:
                raise RuntimeError('Telemetry failed: ' + repr(telemetry_errors))
            row['measurement_after'] = fixture.stats()
            row['metrics_after'] = metrics(fixture)
            row['downstream_after'] = backend_stats(dependency)
            row['generator_after'] = c.proc_metrics(generator.process.pid)
            row['generator_threads_after'] = c.thread_cpu(generator.process.pid)
            fixture.phase = 'draining'
            if generator.process.poll() is not None:
                raise AssertionError('Background stopped during foreground measurement')
            b.save(directory / 'foreground.json', foreground)
            b.save(directory / 'telemetry.json', telemetry)
        if generator.process.wait(timeout=10) != 0:
            raise RuntimeError('Background generator failed')
        raw = b.read(directory / 'background.json')
        if any(s['status'] != 200 for s in raw['samples']):
            raise AssertionError('Background request failures')
        if len(raw['samples']) + len(raw['misses']) != raw['total_offered']:
            raise AssertionError('Background offered slots did not reconcile')
        start, end = foreground['epoch'], foreground['epoch'] + foreground['elapsed']
        latencies = [s['latency_ms'] for s in raw['samples'] if start <= s['epoch'] + s['latency_ms'] / 1000 < end]
        row['background_missed_slots'] = sum(start <= raw['epoch'] + s['slot'] / raw['rate'] < end for s in raw['misses'])
        row['background_dispatch_p99_ms'] = percentile([s['dispatch_lateness_ms'] for s in raw['samples'] if start <= s['epoch'] < end])
        row['background_peak_active'] = raw['peak_active']
        row['generator_cpu_cores'] = raw['measurement_cpu_cores']
        observations = foreground['samples']
        duration = foreground['elapsed']
        total = len(latencies) + len(observations)
        before, after = row['measurement_before'], row['measurement_after']
        measured = [s for s in fixture.record['samples'] if s['phase'] == 'measurement']
        row['measurement'] = dict(background_requests=len(latencies), background_rps=len(latencies) / duration,
            background_p99_ms=percentile(latencies), foreground_requests=len(observations),
            foreground_rps=len(observations) / duration, foreground_missed=foreground['missed'],
            foreground_p99_ms=percentile([s['latency_ms'] for s in observations]),
            scheduled_p99_ms=percentile([s['scheduled_latency_ms'] for s in observations]),
            dispatch_p99_ms=percentile([s['dispatch_lateness_ms'] for s in observations]),
            cpu_us_per_success=(after['cpu_seconds'] - before['cpu_seconds']) * 1e6 / total if total else None,
            rss_peak_mib=max([after['rss_bytes'], *[s.get('process', {}).get('rss_bytes', 0) for s in measured]]) / 1048576,
            cgroup_peak_mib=max([after['cgroup']['current'], *[s['cgroup']['current'] for s in measured]]) / 1048576,
            native_threads_peak=max([after['os_threads'], *[s.get('process', {}).get('os_threads', 0) for s in measured]]),
            pending_mean=statistics.mean(s['pending'] for s in telemetry),
            pending_peak=row['metrics_after']['peak_pending'], waiting_handlers_peak=row['metrics_after']['peak_waiting_handlers'],
            errors=sum(row['metrics_after'][k] - row['metrics_before'][k] for k in ('failures', 'timeouts', 'cancellations')))
        row['target_rate_met'] = len(latencies) >= args.background_rps * duration * .98 and len(observations) >= args.foreground_rps * duration * .98
        row['background_missing_completions'] = max(0, round(args.background_rps * duration) - len(latencies))
        row['generator_busy'] = any((value - row['generator_threads_before'].get(tid, value)) / duration >= .9
                                   for tid, value in row['generator_threads_after'].items())
        row['connections_incomplete'] = row['connections_at_measurement'] != shape(item['protocol'])[0] + 1
        if row['measurement']['errors']:
            raise AssertionError('Downstream operations failed during measurement')
        with Probe(c, port, item['protocol']) as probe:
            require_body(probe.request('/download'), expected)
            require_body(probe.request('/local'), expected)
        row['recovered_metrics'] = await_idle(fixture)
        row['outcome'] = 'pass'
    except Exception as error:
        row.update(outcome='failure', error=repr(error))
    finally:
        stop.set()
        if sampler:
            sampler.join(timeout=6)
            if sampler.is_alive():
                row.update(outcome='failure', error='Telemetry thread did not terminate')
        finish_cleanup(row, fixture, generator, dependency)
    row['host_after'] = b.host_snapshot()
    if (support / 'downstream.json').exists():
        service = b.read(support / 'downstream.json')
        row['downstream_final'] = service['stats']
        if row.get('measurement_before') and row.get('measurement_after'):
            samples = [s for s in service['samples'] if s['outcome'] == 'complete'
                       and row['measurement_before']['time'] <= s['epoch'] < row['measurement_after']['time']]
            row['downstream_lateness_p99_ms'] = percentile([s['scheduling_lateness_ms'] for s in samples])
    row['qualifications'] = ['Short indicative run; host may be in use; editions have different production source revisions']
    for flag in ('generator_busy', 'connections_incomplete'):
        if row.get(flag):
            row['qualifications'].append(flag)
    if not row.get('target_rate_met', False):
        row['qualifications'].append('Offered rate was not met')
    if (row.get('downstream_lateness_p99_ms') or 0) > 5:
        row['qualifications'].append('Downstream timer lateness p99 exceeded 5 ms')
    b.save(directory / 'result.json', row)
    return row


def await_idle(fixture, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = metrics(fixture)
        if state['pending'] == state['waiting_handlers'] == state['active_responses'] == 0:
            return state
        time.sleep(.02)
    raise AssertionError('Fixture did not recover: ' + repr(state))


def await_pending(fixture, minimum=1):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        state = metrics(fixture)
        if state['pending'] >= minimum:
            return state
        time.sleep(.01)
    raise AssertionError('Downstream requests did not overlap: ' + repr(state))


def validate(c, inputs, out, args):
    """Real-network checks against every selected edition/runtime/protocol/handler style."""
    rows = []
    for jdk in args.jdks:
        for role in args.roles:
            for protocol in args.protocols:
                for style in ('blocking', 'async'):
                    item = dict(jdk=jdk, role=role, protocol=protocol, style=style, delay_ms=250)
                    directory = out / f'{jdk}-{role}-{protocol}-{style}'
                    support = out / (directory.name + '-dependency')
                    support.mkdir()
                    row = dict(item, outcome='failure', checks=[])
                    dependency = fixture = None
                    peers = []
                    # Bound but not listening: deterministic connection refusal without a race
                    # for an ephemeral port or any contact with a non-owned service.
                    refused = socket.socket()
                    refused.bind(('127.0.0.1', 0))
                    try:
                        dependency = backend(inputs, support, args.downstream_cpus)
                        port = int(dependency.line('READY ').split()[2])
                        url = f'http://127.0.0.1:{port}/download?delay=250'
                        options = fixture_options(item, url) + [f'-Ddownstream.refusedUrl=http://127.0.0.1:{refused.getsockname()[1]}/']
                        fixture = c.Server(inputs, directory, role, protocol, jdk_version=jdk, java_options=options)
                        fixture.ready()
                        port = fixture.record['ready']['port']
                        expected = hashlib.sha256(c.payload(inputs, 128)).hexdigest()
                        with Probe(c, port, protocol) as probe:
                            assert probe.request('/mode')['body_preview'] == b.expected_mode(role, jdk)
                            started = time.monotonic()
                            require_body(probe.request('/download'), expected)
                            assert time.monotonic() - started >= .25
                            row['checks'] += ['worker policy', 'delayed exact body']
                            # A burst establishes real overlap; it is validation, not a timing result.
                            for _ in range(8):
                                peer = Probe(c, port, protocol); peers.append(peer); peer.send('/download')
                            state = await_pending(fixture, 4)
                            if style == 'async':
                                assert state['waiting_handlers'] == 0
                            else:
                                assert state['peak_waiting_handlers'] >= 1
                            require_body(probe.request('/local'), expected)
                            for peer in peers:
                                require_body(peer.receive(), expected); peer.close()
                            peers.clear()
                            row['overlap'] = state
                            row['checks'] += ['concurrent downstream operations', 'foreground progress']
                            for fault, status in [('status', 502), ('body', 502), ('disconnect', 502),
                                                  ('refusal', 502), ('timeout', 504), ('body_timeout', 504)]:
                                require_body(probe.request('/download?fault=' + fault), expected, status)
                                require_body(probe.request('/download'), expected)
                                await_idle(fixture)
                                row['checks'].append(fault + ' and recovery')
                            before = metrics(fixture)
                            cancelled = Probe(c, port, protocol); peers.append(cancelled)
                            cancelled.send('/download?fault=timeout')
                            await_pending(fixture)
                            if cancelled.peer:
                                cancelled.peer.connection.reset_stream(cancelled.sid)
                                cancelled.peer.flush()
                            else:
                                cancelled.close()
                            cancelled_at = time.monotonic()
                            after = await_idle(fixture)
                            row['front_cancel'] = dict(before=before, after=after,
                                settle_ms=(time.monotonic() - cancelled_at) * 1000,
                                outbound_cancelled=after['cancellations'] > before['cancellations'])
                            row['checks'].append('front cancellation settles within finite bound')
                            require_body(probe.request('/download'), expected)
                            # Do not close the peer before shutting down: the server must cancel this work.
                            pending = Probe(c, port, protocol); peers.append(pending)
                            pending.send('/download?fault=timeout')
                            row['shutdown_pending'] = await_pending(fixture)
                        row['checks'].append('shutdown while outbound request pending')
                        row['outcome'] = 'pass'
                    except Exception as error:
                        row.update(outcome='failure', error=repr(error))
                    finally:
                        finish_cleanup(row, fixture, None, dependency)
                        for peer in peers:
                            peer.close()
                        refused.close()
                    if fixture and (directory / 'server.log').exists():
                        finals = [line.split('FINAL_METRICS ', 1)[1] for line in (directory / 'server.log').read_text().splitlines()
                                  if line.startswith('FINAL_METRICS ')]
                        if len(finals) != 1:
                            row.update(outcome='failure', error='Missing final fixture accounting')
                        else:
                            row['final_metrics'] = json.loads(finals[0])
                            state = row['final_metrics']
                            if any(state[k] for k in ('pending', 'waiting_handlers', 'active_responses')):
                                row.update(outcome='failure', error='Unsettled work at shutdown')
                            if state['started'] != sum(state[k] for k in ('downstream_success', 'failures', 'timeouts', 'cancellations')):
                                row.update(outcome='failure', error='Outbound accounting did not reconcile')
                    b.save(directory / 'validation.json', row)
                    rows.append(row)
                    b.save(out / 'validation.json', dict(complete=False, trials=rows))
                    print('VALIDATE', item, row['outcome'], row.get('error'), flush=True)
                    if row['outcome'] != 'pass':
                        raise RuntimeError('Fixture validation failed; evidence in ' + str(directory))
    injections = []
    for phase in ('startup', 'measurement'):
        item = dict(jdk=args.jdks[0], role=args.roles[0], protocol='http1', style='async', delay_ms=250)
        row = trial(c, inputs, out / ('injected-' + phase), item, args, inject=phase)
        assert row['outcome'] == 'failure' and 'injected ' + phase in row['error']
        for name in ('dependency_lifecycle', 'generator_lifecycle'):
            if name in row:
                assert row[name]['exit_code'] is not None and not row[name]['reader_alive']
                assert not Path('/proc', str(row[name]['pid'])).exists()
        assert row['launch']['unit_status']['MainPID'] == '0'
        injections.append(row)
    b.save(out / 'validation.json', dict(complete=True, trials=rows, injected_failures=injections))


def calibrate(c, inputs, out, args):
    rows = []
    for delay in args.delays:
        directory = out / f'delay-{delay}'
        directory.mkdir(parents=True)
        row = dict(delay_ms=delay, rate=args.background_rps * 2, outcome='failure')
        dependency = generator = None
        try:
            dependency = backend(inputs, directory, args.downstream_cpus)
            port = int(dependency.line('READY ').split()[2])
            generator = Child(load_command(c, inputs, directory, port, 'http1', delay, args.background_rps * 2,
                                           args.warmup, args.duration, calibration=True), directory / 'background.log')
            generator.line('READY ')
            generator.control('START', 'STARTED')
            generator.line('MEASUREMENT', 10)
            row['before'] = c.proc_metrics(dependency.process.pid)
            row['generator_before'] = c.thread_cpu(generator.process.pid)
            generator.line('DRAINING', args.duration + 10)
            row['after'] = c.proc_metrics(dependency.process.pid)
            if generator.process.wait(timeout=5) != 0:
                raise RuntimeError('Calibration generator failed')
            raw = b.read(directory / 'background.json')
            if any(s['status'] != 200 for s in raw['samples']):
                raise AssertionError('Calibration request failures')
            if len(raw['samples']) + len(raw['misses']) != raw['total_offered']:
                raise AssertionError('Calibration offered slots did not reconcile')
            row['achieved_rps'] = sum(s['phase'] == 'measurement' for s in raw['samples']) / args.duration
            row['missed_slots'] = sum(s['phase'] == 'measurement' for s in raw['misses'])
            row['generator_cpu_cores'] = raw['measurement_cpu_cores']
            row['generator_busy'] = raw['measurement_cpu_cores'] >= .9
            row['target_rate_met'] = row['achieved_rps'] >= row['rate'] * .98
            row['outcome'] = 'pass'
        except Exception as error:
            row.update(outcome='failure', error=repr(error))
        finally:
            finish_cleanup(row, None, generator, dependency)
        if (directory / 'downstream.json').exists():
            data = b.read(directory / 'downstream.json')
            row['service'] = data['stats']
            samples = [s for s in data['samples'] if s['outcome'] == 'complete']
            row['lateness_p99_ms'] = percentile([s['scheduling_lateness_ms'] for s in samples])
            row['actual_delay_p99_ms'] = percentile([s['actual_delay_ms'] for s in samples])
            row['capacity_qualified'] = row.get('target_rate_met', False) and not row.get('generator_busy', True) and (row['lateness_p99_ms'] or 0) <= 5
        b.save(directory / 'result.json', row)
        rows.append(row)
        print('CALIBRATE', delay, row.get('achieved_rps'), row.get('lateness_p99_ms'), row['outcome'], flush=True)
        if row['outcome'] != 'pass':
            raise RuntimeError('Dependency calibration failed')
    b.save(out / 'results.json', rows)
    return rows


def report(out, settings, rows, calibration, complete):
    result = dict(settings=settings, trials=rows, calibration=calibration, complete=complete,
        qualification='Indicative short runs, not a release baseline. All foreground and background response bodies are validated. Source revisions differ.')
    b.save(out / 'results.json', result)
    portable = dict(settings={key: settings[key] for key in ('jdks', 'roles', 'protocols', 'delays', 'repeats',
        'warmup', 'duration', 'background_rps', 'foreground_rps', 'server_cpus', 'generator_cpus', 'downstream_cpus',
        'heap_mib', 'cgroup_mib', 'swap_mib', 'client_executor_threads', 'python_runtime', 'python_libraries',
        'manifest_sha256', 'source_identities')}, qualification=result['qualification'], complete=complete,
        calibration=[{key: row.get(key) for key in ('delay_ms', 'rate', 'achieved_rps', 'missed_slots', 'lateness_p99_ms',
                     'actual_delay_p99_ms', 'generator_cpu_cores', 'capacity_qualified', 'outcome')} for row in calibration],
        trials=[{key: row.get(key) for key in ('key', 'jdk', 'role', 'protocol', 'style', 'delay_ms', 'outcome',
                'measurement', 'target_rate_met', 'background_missing_completions', 'background_missed_slots',
                'background_dispatch_p99_ms', 'background_peak_active', 'generator_cpu_cores', 'downstream_lateness_p99_ms',
                'qualifications')} for row in rows])
    b.save(out / 'summary.json', portable)
    columns = [('JDK', 'jdk'), ('Edition', 'role'), ('Ingress', 'protocol'), ('Handler', 'style'), ('Delay ms', 'delay_ms')]
    metrics_columns = [('BG/s', 'background_rps'), ('FG/s', 'foreground_rps'), ('FG p99 ms', 'foreground_p99_ms'),
        ('Scheduled p99 ms', 'scheduled_p99_ms'), ('Missed FG slots', 'foreground_missed'), ('CPU µs/success', 'cpu_us_per_success'),
        ('RSS MiB', 'rss_peak_mib'), ('Cgroup MiB', 'cgroup_peak_mib'), ('OS threads', 'native_threads_peak'),
        ('Pending mean', 'pending_mean'), ('Waiting peak', 'waiting_handlers_peak')]
    parts = ['<!doctype html><meta charset="utf-8"><title>Downstream wait comparison</title>',
        '<style>body{font:14px system-ui;margin:24px}table{border-collapse:collapse}th,td{padding:7px;border:1px solid #bbb;text-align:right}th{position:sticky;top:0;background:#eee}p{max-width:1000px}</style>',
        '<h1>Downstream wait comparison</h1><p>' + html.escape(result['qualification']) + '</p>',
        '<p>400 background requests/s, 100 independent fast requests/s by default; 256 background slots. '
        'Both generators skip overdue slots. Background remains active for one extra second to cover control/probe overhead; '
        'BG/s counts completions within the exact foreground measurement window. CPU includes the shared outbound JDK client. '
        'RSS/cgroup include off-heap memory. Memory and thread peaks are sampled during measurement.</p>',
        '<p><a href="results.json">Raw ledger</a> · <a href="settings.json">Configuration and identities</a> · '
        '<a href="summary.json">Portable summary</a></p>',
        '<table><tr>' + ''.join('<th>' + title + '</th>' for title, _ in columns + metrics_columns) + '<th>Qualifications</th></tr>']
    for row in rows:
        values = [row[key] for _, key in columns] + [row.get('measurement', {}).get(key) for _, key in metrics_columns]
        link = Path(row['evidence_directory']).relative_to(out) / 'result.json'
        parts.append('<tr>' + ''.join('<td>' + html.escape(f'{v:.3f}' if isinstance(v, float) else str(v)) + '</td>' for v in values)
            + '<td><a href="' + html.escape(str(link)) + '">' + html.escape(row['outcome']) + '</a>: '
            + html.escape('; '.join(row.get('qualifications', []))) + '</td></tr>')
    parts.append('</table>')
    (out / 'report.html').write_text('\n'.join(parts))


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'run', 'validate'))
    parser.add_argument('--inputs', type=Path, required=True, help='Parent frozen inputs for prepare; prepared downstream inputs otherwise')
    parser.add_argument('--out', type=Path, required=True, help='New directory, never overwritten')
    parser.add_argument('--jdks', default='21,25')
    parser.add_argument('--roles', default='mu3,blocking,nio')
    parser.add_argument('--protocols', default='http1,h2')
    parser.add_argument('--delays', default='0,250', help='Milliseconds; 50 is an optional intermediate case')
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--duration', type=int, default=5)
    parser.add_argument('--background-rps', type=int, default=400)
    parser.add_argument('--foreground-rps', type=int, default=100)
    parser.add_argument('--server-cpus', default='0-3,8-11')
    parser.add_argument('--generator-cpus', default='4-6,12-14')
    parser.add_argument('--downstream-cpus', default='7,15')
    args = parser.parse_args()
    try:
        args.jdks = [int(v) for v in args.jdks.split(',')]
        args.delays = [int(v) for v in args.delays.split(',')]
        args.roles = args.roles.split(',')
        args.protocols = args.protocols.split(',')
        if not set(args.jdks) <= {21, 25} or not args.jdks or not args.delays or any(v not in (0, 50, 250) for v in args.delays):
            raise ValueError('Use JDKs 21/25 and delays 0/50/250')
        if not set(args.roles) <= set(b.ROLES) or not set(args.protocols) <= {'http1', 'h2'}:
            raise ValueError('Invalid role or protocol')
        if any(getattr(args, k) < 1 for k in ('warmup', 'duration', 'repeats', 'background_rps', 'foreground_rps')):
            raise ValueError('Durations, rates and repeats must be positive')
        cpu_groups = [b.cpu_set(getattr(args, k)) for k in ('server_cpus', 'generator_cpus', 'downstream_cpus')]
        if any(cpu_groups[i] & cpu_groups[j] for i in range(3) for j in range(i)):
            raise ValueError('CPU groups must not overlap')
    except ValueError as error:
        parser.error(str(error))
    return args


def main():
    args = options()
    inputs, out = args.inputs.resolve(), args.out.resolve()
    if args.action == 'prepare':
        prepare(inputs, out)
        return
    c = b.load_harness(inputs / 'harness')
    manifest = b.verify_manifest(inputs, c)
    if b.digest(inputs / 'inputs/PerformanceFixture.java') != b.digest(HERE / 'downstream/PerformanceFixture.java'):
        raise RuntimeError('Prepared fixture differs from checked-in source')
    c.SERVER_CPUS, c.GENERATOR_CPUS = args.server_cpus, args.generator_cpus
    os.sched_setaffinity(0, b.cpu_set(args.generator_cpus))
    d.install_shutdown_observer(c)
    d.configure_descriptors(1024)
    out.mkdir(parents=True, exist_ok=False)
    sources = [Path(__file__).resolve(), HERE / 'downstream_service.py', HERE / 'downstream_load.py',
               HERE / 'downstream/PerformanceFixture.java', Path(b.__file__), Path(d.__file__)]
    settings = {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()}
    settings.update(manifest_sha256=b.digest(inputs / 'manifest.json'), source_identities=manifest['provenance'],
                    controller_sources={str(p): b.digest(p) for p in sources}, heap_mib=512, cgroup_mib=1024,
                    swap_mib=0, client_executor_threads=8, host_before=b.host_snapshot(),
                    python_runtime=sys.version, openssl=ssl.OPENSSL_VERSION,
                    python_libraries={name: importlib.metadata.version(name) for name in ('h2', 'hpack', 'hyperframe')},
                    cpu_topology=subprocess.check_output(['lscpu', '-e=CPU,CORE,SOCKET,ONLINE'], text=True))
    for index, source in enumerate(sources):
        shutil.copy2(source, out / (str(index) + '-' + source.name))
    b.save(out / 'settings.json', settings)
    if args.action == 'validate':
        validate(c, inputs, out, args)
    else:
        calibration = calibrate(c, inputs, out / 'calibration', args)
        rows = []
        items = plan(args.jdks, args.repeats, args.delays, args.protocols, args.roles)
        b.save(out / 'plan.json', items)
        for item in items:
            print('START', item['key'], flush=True)
            row = trial(c, inputs, out / 'trials' / item['key'], item, args)
            if any(not r.get('capacity_qualified') for r in calibration):
                row['qualifications'].append('Dependency calibration did not establish 2x capacity with p99 timer lateness <=5ms')
            rows.append(row)
            report(out, settings, rows, calibration, False)
            print('RESULT', row['outcome'], row.get('measurement'), row.get('error'), flush=True)
            if row['outcome'] != 'pass':
                raise RuntimeError('Trial failed; retained evidence in ' + str(out))
    b.verify_manifest(inputs, c)
    for filename, expected in settings['controller_sources'].items():
        if b.digest(Path(filename)) != expected:
            raise AssertionError('Controller changed during run')
    if args.action == 'run':
        report(out, settings, rows, calibration, True)
    print('COMPLETE', out, flush=True)


if __name__ == '__main__':
    main()
