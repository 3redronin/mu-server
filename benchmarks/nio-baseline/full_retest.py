#!/usr/bin/env python3
"""Prepare, validate and resume a frozen three-way performance campaign.

Run the copied code/full_retest.py after preparation. Each trial is a fresh
worker process; each comparison group contains all selected editions. Historical
evidence and production code are never modified by this runner.
"""
import argparse
import contextlib
import fcntl
import hashlib
import html
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import signal
import statistics
import subprocess
import sys
import time
import traceback
from types import SimpleNamespace

import baseline as b
import connection_density as d
import downstream_wait as w
import roundness as r
import retest_matrix as matrix

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
WORK = REPO.parent
DEFAULT_INPUTS = WORK / 'nonblocking-validation/deep-profile-20261023/release-selected/after-async'


def python_runtime():
    return dict(executable=sys.executable, version=sys.version,
                packages={name: importlib.metadata.version(name) for name in ('h2', 'hpack', 'hyperframe')})


def code_inventory(directory):
    return {str(p.relative_to(directory)): b.digest(p) for p in sorted(directory.rglob('*'))
            if p.is_file() and '__pycache__' not in p.parts}


def prepare(args):
    root, parent = args.out.resolve(), args.source_inputs.resolve()
    c = b.load_harness(parent / 'harness')
    original = b.verify_manifest(parent, c)
    sources = {role: c.process.load_build(role) for role in b.ROLES}
    repository = args.repository.resolve()
    checkout = c.process.identity(repository)
    if checkout['source_sha256'] != sources['nio']['source']['source_sha256']:
        raise RuntimeError('NIO inputs do not match current production. Prepare verified current NIO inputs first; never copy unverified target/classes.')
    settings = dict(profile=args.profile, warmup=args.warmup or (20 if args.profile == 'full' else 1),
        duration=args.duration or (60 if args.profile == 'full' else 3), repeats=args.repeats or (3 if args.profile == 'full' else 1),
        jdks=args.jdks, roles=args.roles, families=args.families, cases=args.cases,
        server_cpus='0-3,8-11', generator_cpus='4-7,12-15', downstream_generator_cpus='4-6,12-14',
        downstream_cpus='7,15', created_epoch=time.time(), checkout=checkout,
        lock_path=str((args.parent_campaign.resolve().parent if args.parent_campaign else root.parent) / '.mu-full-retest.lock'),
        source_identities={role: value['source'] for role, value in sources.items()},
        heap_mib=512, cap_mib=1024, swap_mib=0,
        python=python_runtime(), cpu_topology=subprocess.check_output(['lscpu', '-e=CPU,CORE,SOCKET,ONLINE'], text=True))
    if min(settings['warmup'], settings['duration'], settings['repeats']) < 1:
        raise ValueError('Durations and repeats must be positive')
    if settings['profile'] == 'full' and (settings['warmup'] < 10 or settings['duration'] < 20 or settings['repeats'] < 3):
        raise ValueError('Full campaigns require at least 10s warm-up, 20s measurement and three balanced repeats')
    planned = matrix.plan(settings)
    root.mkdir(parents=True, exist_ok=False)
    (root / 'code/downstream').mkdir(parents=True)
    for filename in HERE.glob('*.py'):
        shutil.copy2(filename, root / 'code' / filename.name)
    shutil.copy2(HERE / 'downstream/PerformanceFixture.java', root / 'code/downstream/PerformanceFixture.java')
    fixture_sources = {
        'sync': parent.parent / 'after-sync/inputs/PerformanceFixture.java',
        'async': parent / 'inputs/PerformanceFixture.java',
        'bounded': parent.parent / 'after-bounded/inputs/PerformanceFixture.java',
        'streaming': WORK / 'nonblocking-validation/baseline-20261018-quiet/streaming/inputs/PerformanceFixture.java',
        'downstream': HERE / 'downstream/PerformanceFixture.java'}
    if args.parent_campaign:
        fixture_sources = {name: args.parent_campaign.resolve() / 'inputs' / name / 'inputs/PerformanceFixture.java'
                           for name in fixture_sources}
    # Keep validation independent of the selected measurement subset.
    used = set(fixture_sources)
    for flavor in sorted(used):
        out = root / 'inputs' / flavor
        shutil.copytree(parent / 'harness', out / 'harness', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        # Older harness reports assumed evidence lived inside the input directory.
        # Keep the new immutable inputs separate from attempts; change only links.
        harness_file = out / 'harness/compare_performance.py'
        harness_source = harness_file.read_text()
        harness_source = harness_source.replace("str(directory.relative_to(out))", "os.path.relpath(directory, out)")
        harness_file.write_text(harness_source)
        shutil.copytree(parent / 'inputs', out / 'inputs')
        filename = fixture_sources[flavor]
        source = filename.read_text()
        # The same optional owner setting is compiled for all editions, but only
        # NIO gets this property at runtime. Keep every body/write style unchanged.
        if flavor != 'downstream' and 'String transportThreads' not in source:
            needle = 'MuServerBuilder builder = MuServerBuilder.muServer().withInterface("127.0.0.1");'
            if source.count(needle) != 1: raise AssertionError('Unrecognized fixture builder')
            source = source.replace(needle, needle + '\n        String transportThreads = System.getProperty("comparison.nioThreads");\n'
                '        if (transportThreads != null) builder.withNioThreads(Integer.parseInt(transportThreads));')
        fixture = out / 'inputs/PerformanceFixture.java'
        fixture.write_text(source)
        for role in b.ROLES:
            metadata = json.loads(json.dumps(sources[role]))
            destination = out / 'frozen/build' / role
            destination.mkdir(parents=True)
            paths = [Path(p) for p in metadata['classpath'].split(os.pathsep) if not p.endswith('.jar')]
            production = [p for p in paths if not (p / 'validation/PerformanceFixture.class').exists()]
            if len(production) != 1: raise ValueError('Expected one production directory')
            shutil.copytree(production[0], destination / 'production-classes')
            classes = destination / 'performance-classes'; classes.mkdir()
            cp = os.pathsep.join(map(str, [classes, destination / 'production-classes', *metadata['dependencies']]))
            c.process.run_logged([c.process.jdk_path(21) / 'bin/javac', '--release', '11', '-cp', cp,
                                  '-d', classes, fixture], out / (role + '-javac.log'))
            metadata.update(classpath=cp, fixture_sources={str(fixture): b.digest(fixture)},
                class_outputs={str(p): c.process.output_inventory(p) for p in (classes, destination / 'production-classes')})
            b.save(destination / 'build.json', metadata)
        files = {str(p.relative_to(out)): b.digest(p) for base in (out / 'harness', out / 'inputs')
                 for p in base.rglob('*') if p.is_file() and '__pycache__' not in p.parts}
        for role in b.ROLES:
            filename = out / 'frozen/build' / role / 'build.json'; files[str(filename.relative_to(out))] = b.digest(filename)
        b.save(out / 'manifest.json', dict(files=files, provenance=settings['source_identities'], tool=original['tool'],
            fixture_policy=flavor + ': identical source compiled for all three editions',
            fixture_origin=dict(path=str(fixture_sources[flavor]), sha256=b.digest(fixture_sources[flavor]))))
        b.verify_manifest(out, c)
    settings['manifests'] = {flavor: b.digest(root / 'inputs' / flavor / 'manifest.json') for flavor in sorted(used)}
    settings['code'] = code_inventory(root / 'code')
    settings['runtime_versions'] = {str(jdk): subprocess.check_output([str(c.process.jdk_path(jdk) / 'bin/java'), '-version'],
                                      stderr=subprocess.STDOUT, text=True) for jdk in settings['jdks']}
    b.save(root / 'settings.json', settings)
    b.save(root / 'plan.json', planned)
    b.save(root / 'state.json', dict(groups={}, sessions=[], complete=False))
    report(root)
    print('PREPARED', root, 'groups', len(planned), 'JVM trials', sum(len(g['roles']) for g in planned), flush=True)
    print('Use:', sys.executable, root / 'code/full_retest.py', 'validate --out', root, flush=True)


def load(root, require_frozen=True):
    settings = b.read(root / 'settings.json')
    if python_runtime() != settings['python']: raise RuntimeError('Python runtime or dependencies changed')
    if code_inventory(root / 'code') != settings['code']:
        raise RuntimeError('Frozen controller changed; do not resume this campaign')
    if require_frozen and Path(__file__).resolve() != root / 'code/full_retest.py':
        raise RuntimeError('Execute the frozen code/full_retest.py inside the campaign')
    planned = b.read(root / 'plan.json')
    if planned != matrix.plan(settings): raise RuntimeError('Campaign plan changed')
    c = b.load_harness(root / 'inputs/async/harness')
    for flavor, expected in settings['manifests'].items():
        inp = root / 'inputs' / flavor
        if b.digest(inp / 'manifest.json') != expected: raise RuntimeError('Input manifest changed')
        b.verify_manifest(inp, c)
    for jdk, expected in settings['runtime_versions'].items():
        actual = subprocess.check_output([str(c.process.jdk_path(int(jdk)) / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
        if actual != expected: raise RuntimeError('JDK changed during campaign')
    return settings, planned, c


def start_ticks(pid):
    try: return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]
    except FileNotFoundError: return None


@contextlib.contextmanager
def owned_processes(directory):
    original = subprocess.Popen
    def tracked(command, *args, **kwargs):
        process = original(command, *args, **kwargs)
        if isinstance(command, (list, tuple)) and any(token in str(part) for part in command
            for token in ('h2load', 'downstream_load.py', 'downstream_service.py', 'systemd-run')):
            with (directory / 'owned.jsonl').open('a') as out:
                out.write(json.dumps(dict(pid=process.pid, start_ticks=start_ticks(process.pid),
                    command=list(map(str, command)), process_group=bool(kwargs.get('start_new_session')))) + '\n')
                out.flush(); os.fsync(out.fileno())
        return process
    subprocess.Popen = tracked
    try: yield
    finally: subprocess.Popen = original


def leftovers(directory, cleanup=False):
    """Only touch recorded PIDs with matching kernel start times, and recorded JVM units."""
    found = []
    journals = [(p, [p.read_text()]) for p in directory.rglob('worker.json')]
    journals += [(p, p.read_text().splitlines()) for p in directory.rglob('owned.jsonl')]
    for journal, lines in journals:
        for line in lines:
            item = json.loads(line)
            if item['start_ticks'] is None or start_ticks(item['pid']) != item['start_ticks']: continue
            found.append(dict(kind='child', **item))
            if cleanup:
                kill = os.killpg if item['process_group'] else os.kill
                try: kill(item['pid'], signal.SIGTERM)
                except ProcessLookupError: continue
                deadline = time.monotonic() + 3
                while start_ticks(item['pid']) == item['start_ticks'] and time.monotonic() < deadline: time.sleep(.05)
                if start_ticks(item['pid']) == item['start_ticks']:
                    try: kill(item['pid'], signal.SIGKILL)
                    except ProcessLookupError: pass
    for launch in directory.rglob('launch.json'):
        unit = b.read(launch).get('unit')
        if not unit or not unit.startswith('mu-survey-') or not unit.endswith('.service'):
            raise RuntimeError('Invalid recorded server unit')
        result = subprocess.run(['systemctl', '--user', 'show', unit, '--property=MainPID,ActiveState'],
                                text=True, capture_output=True, timeout=10)
        values = dict(line.split('=', 1) for line in result.stdout.splitlines())
        if not values: raise RuntimeError('Unable to inspect owned unit ' + unit)
        if values.get('MainPID', '0') != '0' or values.get('ActiveState') not in ('inactive', 'failed'):
            found.append(dict(kind='unit', unit=unit, properties=values))
            if cleanup:
                subprocess.run(['systemctl', '--user', 'stop', unit], check=True, timeout=20)
                subprocess.run(['systemctl', '--user', 'reset-failed', unit], capture_output=True, timeout=10)
    return found


def resource_evidence(row, directory):
    if row.get('outcome') == 'pass': return None
    if row.get('launch', {}).get('unit_status', {}).get('Result') == 'oom-kill': return 'cgroup OOM kill'
    for filename in directory.rglob('server.log'):
        text = filename.read_text(errors='replace')
        for marker in ('OutOfMemoryError', 'OutOfDirectMemoryError', 'unable to create native thread'):
            if marker in text: return marker
    for sample in row.get('launch', {}).get('samples', []):
        events = sample.get('cgroup', {}).get('events', {})
        if events.get('oom_kill', 0): return 'cgroup OOM kill'
    return None


def enrich(row, group, settings, host_state):
    samples = [s for s in row.get('launch', {}).get('samples', []) if s.get('phase') == 'measurement']
    measurement = row.setdefault('measurement', {})
    measurement.update({key: value for key, value in row.get('tails', {}).items()
                        if key.endswith('_ms') and isinstance(value, (int, float))})
    if samples:
        measurement.update(rss_peak_mib=max(s.get('process', {}).get('rss_bytes', 0) for s in samples) / 1048576,
            cgroup_peak_mib=max(s.get('cgroup', {}).get('current', 0) for s in samples) / 1048576,
            native_threads_peak=max(s.get('process', {}).get('os_threads', 0) for s in samples))
    before = row.get('measurement_before', row.get('before'))
    after = row.get('measurement_after', row.get('after'))
    if before and after:
        measurement['cpu_seconds'] = after['cpu_seconds'] - before['cpu_seconds']
        # Fairness CPU includes bulk work; dividing by only the small responses is misleading.
        successes = measurement.get('requests', 0) + row.get('background_during_foreground', 0)
        if 'bulk_per_second' in measurement: successes += sum(measurement['bulk_per_second'])
        if successes: measurement['cpu_us_per_success'] = measurement['cpu_seconds'] * 1e6 / successes
    rate = group['target_rps']
    if rate is not None:
        actual = measurement.get('requests_per_second', measurement.get('requests', 0) / group['duration'])
        row['target_rate_met'] = actual >= rate * .98
    exclusions = b.exclusions(row, host_state, 'smoke' if group['phase'] == 'tuning' else settings['profile'])
    if row.get('probe_cpu_cores', 0) >= .9: exclusions.append('Python probe CPU saturation')
    if row.get('background_forced'): exclusions.append('Background generator forced cleanup')
    if (row.get('downstream_lateness_p99_ms') or 0) > 5:
        exclusions.append('Downstream timer lateness p99 exceeded 5 ms')
    row.update(performance_exclusions=exclusions, family=group['family'], fixture=group['fixture'],
               workload=group['name'], jdk=group['jdk'], repeat=group['repeat'])
    return row


def selected_heaps(root, state, settings):
    path = root / 'heap-selection.json'
    if path.exists(): return b.read(path)
    candidates = []
    for record in state['groups'].values():
        if record['status'] == 'complete':
            for filename in record['results']:
                row = b.read(root / filename)
                if row['family'] == 'heap-tuning': candidates.append(row)
    expected = 16 * len(settings['jdks']) * len(settings['roles'])
    if len(candidates) != expected: raise RuntimeError('Complete independent heap tuning before confirmation')
    selected = {}
    for cap in (512, 1024):
        for jdk in settings['jdks']:
            for role in settings['roles']:
                rows = [r for r in candidates if (r['cap_mib'], r['jdk'], r['role']) == (cap, jdk, role)]
                zeros = {r['heap']: r for r in rows if r['idle'] == 0}
                dense = [r for r in rows if r['idle'] > 0]
                for row in dense:
                    # Selection additionally uses the zero-cohort control; all fallbacks remain visible.
                    row['zero_control_ok'] = zeros[row['heap']]['outcome'] == 'pass' and zeros[row['heap']]['target_rate_met']
                healthy = [r for r in dense if r.get('zero_control_ok') and r['outcome'] == 'pass'
                           and r['target_rate_met'] and not r.get('generator_busy') and not r.get('connections_incomplete')]
                winner = min(healthy, key=lambda r: int(r['heap'][:-1])) if healthy else None
                heap = winner['heap'] if winner else d.choose_heap(dense)
                selected[f'{cap}/{jdk}/{role}'] = dict(heap=heap, healthy_candidate=winner is not None,
                    candidates=[dict(heap=r['heap'], outcome=r['outcome'], target_rate_met=r['target_rate_met'],
                                     zero_control_ok=r.get('zero_control_ok', False)) for r in dense])
    b.save(path, selected)
    return selected


def trial_worker(args):
    root, directory = args.out.resolve(), args.directory.resolve()
    settings, planned, c = load(root)
    group = next(g for g in planned if g['id'] == args.group)
    role = args.role
    if role not in group['roles']: raise ValueError('Unexpected edition')
    c.SERVER_CPUS = settings['server_cpus']
    c.GENERATOR_CPUS = settings['downstream_generator_cpus'] if group['engine'] == 'downstream' else settings['generator_cpus']
    os.sched_setaffinity(0, b.cpu_set(c.GENERATOR_CPUS))
    d.configure_descriptors(4096)
    d.install_shutdown_observer(c)
    inp = root / 'inputs' / group['fixture']
    manifest = b.verify_manifest(inp, c)
    tool = Path(manifest['tool']['path'])
    spec = dict(settings, warmup=group['warmup'], duration=group['duration'], host_state=args.host_state,
                cap_mib=group.get('cap_mib', 1024), heap=group.get('heap', '512m'),
                direct_memory_mib=group.get('direct_memory_mib'), heap_by_edition={})
    if group.get('selected_heap'):
        selected = b.read(root / 'heap-selection.json')[f'{group["cap_mib"]}/{group["jdk"]}/{role}']
        spec['heap'] = selected['heap']
    item = dict(group, key=group['id'] + '/' + role, role=role, workload=group['name'],
                observe_overlap=group['family'] == 'rounded')
    base = c.Server
    class Configured(base):
        def __init__(self, *a, **kw):
            options = list(kw.get('java_options', []))
            if group.get('collector'): options += [f'-XX:+Use{group["collector"]}GC']
            if role == 'nio': options += ['-Dcomparison.nioThreads=4']
            kw['java_options'] = options
            super().__init__(*a, **kw)
    c.Server = Configured
    row = dict(outcome='failure')
    cpu_started, began = time.process_time(), time.monotonic()
    directory.mkdir(parents=True, exist_ok=False)
    evidence = directory / 'evidence'
    try:
        with owned_processes(directory):
            if group['engine'] == 'density':
                row = d.trial(c, inp, evidence, item, spec, tool)
                row.update(cap_mib=spec['cap_mib'], heap=spec['heap'], idle=group['idle'])
            elif group['engine'] == 'native':
                row = r.native_trial(c, inp, evidence, item, spec, tool)
            elif group['engine'] == 'shared':
                row = r.fairness_trial(c, inp, evidence, item, spec)
                r.finish_row(row, args.host_state, settings['profile'])
            elif group['engine'] == 'writers':
                import busy_writer_fairness as writer
                options = SimpleNamespace(**dict(spec, protocol=group['protocol'], nio_threads=4,
                    background_clients=group['background_clients'], foreground_rps=1000))
                row = d.native_with_cleanup(c, tool, lambda: writer.trial(c, tool, inp, evidence,
                    group['jdk'], role, group['repeat'], options, role=role))
            elif group['engine'] == 'h2-writers':
                import h2_writer_fairness as writer
                options = SimpleNamespace(**dict(spec, nio_threads=4, rate=500,
                    stream_window=group['stream_window'], background=group['background']))
                row = writer.trial(c, inp, evidence, group['jdk'], role, group['repeat'], options, role=role)
            elif group['engine'] == 'downstream':
                options = SimpleNamespace(**dict(spec, background_rps=400, foreground_rps=100))
                row = w.trial(c, inp, evidence, item, options)
                row['qualifications'] = ['Global pacing; body-validated foreground and background; differing production revisions']
            else: raise ValueError('Unknown engine')
    except Exception as error:
        row.update(outcome='failure', error=repr(error), traceback=traceback.format_exc())
        launches = list(directory.rglob('launch.json'))
        if launches: row['launch'] = b.read(launches[-1])
    finally:
        row['probe_cpu_cores'] = (time.process_time() - cpu_started) / max(.001, time.monotonic() - began)
        live = leftovers(directory, cleanup=True)
        row['supervisor_cleanup'] = live
        if live:
            row.update(outcome='cleanup failure', error='Owned processes outlived trial cleanup')
        if leftovers(directory):
            row.update(outcome='cleanup failure', error='Owned processes still alive after supervisor cleanup')
    error = d.shutdown_error(row.get('launch', {}))
    if error and row.get('outcome') == 'pass': row.update(outcome='cleanup failure', error=error)
    if group.get('collector') and group['collector'] == 'G1' and 'G1' not in row.get('launch', {}).get('ready', {}).get('gc', ''):
        row.update(outcome='failure', error='Requested G1 collector not observed')
    resource = resource_evidence(row, directory)
    if resource and group['engine'] == 'density':
        row.update(original_outcome=row['outcome'], outcome='resource limit', resource_evidence=resource,
                   cap_mib=spec['cap_mib'], heap=spec['heap'], idle=group['idle'])
    row.update(role=role, evidence_directory=str(evidence), source_sha256=settings['source_identities'][role]['source_sha256'])
    enrich(row, group, settings, args.host_state)
    if group['engine'] == 'downstream' and row.get('measurement', {}).get('foreground_rps', 0) < 98:
        row['target_rate_met'] = False
        row['performance_exclusions'].append('Foreground offered rate not met')
    b.save(directory / 'result.json', row)
    print('RESULT', group['id'], role, row['outcome'], row.get('error', ''), flush=True)
    return 0 if row['outcome'] in ('pass', 'resource limit') else 1


@contextlib.contextmanager
def campaign_lock(root):
    # The shared parent lock prevents two newly prepared campaigns from loading
    # the same workstation concurrently. It is not a lock on unrelated user work.
    with Path(b.read(root / 'settings.json')['lock_path']).open('a') as lock:
        try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError: raise RuntimeError('Another campaign is running in this evidence directory')
        yield


def run(args):
    root = args.out.resolve()
    settings, planned, c = load(root)
    if settings['profile'] == 'full' and args.host_state != 'quiet':
        raise ValueError('Run a full baseline only when the user has released the machine; use a separate smoke campaign while busy')
    receipt = root / 'validation.json'
    if settings['profile'] == 'full' and (not receipt.exists() or not b.read(receipt).get('complete')):
        raise RuntimeError('Run validate successfully before starting full measurements')
    if settings['profile'] == 'full' and b.read(receipt).get('settings_sha256') != b.digest(root / 'settings.json'):
        raise RuntimeError('Validation receipt does not match frozen campaign')
    with campaign_lock(root):
        state = b.read(root / 'state.json')
        session = dict(id=len(state['sessions']), start_epoch=time.time(), host_state=args.host_state,
                       host_before=b.host_snapshot(), max_hours=args.max_hours, max_groups=args.max_groups)
        state['sessions'].append(session)
        b.save(root / 'state.json', state)
        pause = root / 'pause.request'
        pause.unlink(missing_ok=True)
        stop = [False]
        old_handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
        for sig in old_handlers: signal.signal(sig, lambda *_: stop.__setitem__(0, True))
        began, completed, calibration = time.monotonic(), 0, None
        try:
            for group in planned:
                previous = state['groups'].get(group['id'])
                if previous and previous['status'] == 'complete': continue
                if stop[0] or pause.exists() or (args.max_groups and completed >= args.max_groups): break
                if args.max_hours and time.monotonic() - began >= args.max_hours * 3600: break
                if shutil.disk_usage(root).free < 20 * 1024**3:
                    session['checkpoint_reason'] = 'Less than 20 GiB disk space remains'
                    print('CHECKPOINT:', session['checkpoint_reason'], flush=True)
                    break
                if previous and not args.retry_failed:
                    raise RuntimeError('An interrupted/failed group exists. Inspect it, then explicitly use --retry-failed; previous evidence is retained')
                if previous:
                    attempt_path = root / previous['directory']
                    recovered = leftovers(attempt_path, cleanup=True)
                    b.save(attempt_path / 'resume-cleanup.json', recovered)
                    if leftovers(attempt_path): raise RuntimeError('Interrupted trial still owns live processes')
                if group.get('selected_heap'): selected_heaps(root, state, settings)
                if group['engine'] == 'downstream' and calibration is None:
                    support = root / 'calibration' / f'session-{session["id"]}'
                    c.SERVER_CPUS, c.GENERATOR_CPUS = settings['server_cpus'], settings['downstream_generator_cpus']
                    options = SimpleNamespace(background_rps=400, warmup=3, duration=5 if settings['profile'] == 'smoke' else 10,
                                              delays=[0, 50, 250], downstream_cpus=settings['downstream_cpus'])
                    support.mkdir(parents=True)
                    with owned_processes(support):
                        try: calibration = w.calibrate(c, root / 'inputs/downstream', support / 'runs', options)
                        finally:
                            if leftovers(support, cleanup=True): raise RuntimeError('Calibration leaked owned processes')
                    if any(not row.get('capacity_qualified') for row in calibration):
                        raise RuntimeError('Downstream calibration did not establish spare capacity; no downstream comparison started this session')
                    if leftovers(support, cleanup=True): raise RuntimeError('Calibration leaked owned processes')
                    session['calibration'] = str(support.relative_to(root))
                number = len(previous['attempts']) if previous else 0
                directory = root / 'attempts' / group['id'] / str(number)
                directory.mkdir(parents=True, exist_ok=False)
                record = dict(status='running', directory=str(directory.relative_to(root)), session=session['id'],
                              results=[], attempts=[*(previous['attempts'] if previous else []), str(directory.relative_to(root))])
                state['groups'][group['id']] = record
                b.save(root / 'state.json', state)
                failed = False
                for role in group['roles']:
                    target = directory / role
                    command = [sys.executable, str(root / 'code/full_retest.py'), '_trial', '--out', str(root),
                        '--group', group['id'], '--role', role, '--directory', str(target), '--host-state', args.host_state]
                    print('START', group['id'], role, 'attempt', number, flush=True)
                    with (directory / (role + '-worker.log')).open('w') as log:
                        worker = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                        b.save(directory / (role + '-supervisor') / 'worker.json', dict(pid=worker.pid,
                            start_ticks=start_ticks(worker.pid), command=command, process_group=True))
                        try: code = worker.wait(timeout=max(300, group['warmup'] + group['duration'] + 240))
                        except BaseException:
                            os.killpg(worker.pid, signal.SIGKILL); worker.wait(timeout=5)
                            leftovers(target, cleanup=True)
                            raise
                    result_path = target / 'result.json'
                    if result_path.exists():
                        record['results'].append(str(result_path.relative_to(root)))
                        result = b.read(result_path)
                        print('RESULT', group['id'], role, result['outcome'], result.get('error', ''), flush=True)
                    else: result = dict(outcome='worker failure')
                    failed |= code != 0 or result['outcome'] not in ('pass', 'resource limit')
                    b.save(root / 'state.json', state)
                    if leftovers(target, cleanup=True): failed = True
                    if failed: break
                record['status'] = 'failed' if failed else 'complete'
                b.save(root / 'state.json', state)
                report(root)
                if failed: raise RuntimeError('Group failed; inspect ' + str(directory))
                completed += 1
            state['complete'] = all(state['groups'].get(g['id'], {}).get('status') == 'complete' for g in planned)
        finally:
            session.update(end_epoch=time.time(), host_after=b.host_snapshot())
            b.save(root / 'state.json', state)
            report(root)
            for sig, old in old_handlers.items(): signal.signal(sig, old)
        load(root)
        print('COMPLETE' if state['complete'] else 'CHECKPOINT', root / 'report.html', flush=True)


VALIDATION_CASES = ['sync-small', 'async-h2bulk', 'streaming-h2-768', 'burst-download-window-1023',
    'fairness-same-connection-paused-16', 'density-https1-4096', 'writers-http1-0', 'writers-http1-8',
    'writers-https1-8', 'writers-h2-65535-0', 'writers-h2-65535-1', 'downstream-h2-async-250',
    'bounded-tls-128-8', 'gc-G1-small', 'gzip-high', 'raw-upload-h2', 'multipart-h2']


def validate(args):
    root = args.out.resolve()
    # Post-processing can evolve without changing already measured inputs/code.
    # All measurement workers still execute the original verified snapshot.
    settings, _, c = load(root, require_frozen=False)
    if (root / 'validation.json').exists():
        if b.read(root / 'validation.json').get('complete'):
            print('ALREADY VALIDATED', root, flush=True); return
        raise RuntimeError('Incomplete validation receipt; preserve evidence and inspect before proceeding')
    validation = root / 'validation'
    validation.mkdir(exist_ok=True)
    with (validation / 'unit-tests.log').open('w') as log:
        subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(root / 'code'), '-p', 'test_*.py', '-v'],
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=120)
    smoke = validation / 'smoke'
    if not smoke.exists():
        prepare(SimpleNamespace(out=smoke, source_inputs=root / 'inputs/async', repository=Path(settings['checkout']['path']),
            parent_campaign=root, profile='smoke', warmup=1, duration=3, repeats=1, jdks=settings['jdks'],
            roles=settings['roles'], families=[], cases=VALIDATION_CASES))
    subprocess.run([sys.executable, str(smoke / 'code/full_retest.py'), 'run', '--out', str(smoke),
                    '--host-state', 'busy', *(['--retry-failed'] if args.retry_failed else [])], check=True)
    subprocess.run([sys.executable, str(Path(__file__).resolve()), 'audit', '--out', str(smoke)], check=True)
    prior_faults = sorted(validation.glob('faults-*'), key=lambda p: int(p.name.split('-')[-1]))
    faults = prior_faults[-1] if prior_faults else validation / 'faults-0'
    if not (faults / 'validation.json').exists() or not b.read(faults / 'validation.json').get('complete'):
        if faults.exists():
            if not args.retry_failed: raise RuntimeError('Prior incomplete fault checks retained; inspect, then use --retry-failed')
            leftovers(faults, cleanup=True)
            if leftovers(faults): raise RuntimeError('Prior fault check still owns live processes')
            faults = validation / f'faults-{len(prior_faults)}'
        with campaign_lock(root):
            faults.mkdir()
            c.SERVER_CPUS, c.GENERATOR_CPUS = settings['server_cpus'], settings['downstream_generator_cpus']
            d.install_shutdown_observer(c)
            options = SimpleNamespace(jdks=settings['jdks'], roles=settings['roles'], protocols=['http1', 'h2'],
                downstream_cpus=settings['downstream_cpus'], warmup=1, duration=3, background_rps=400, foreground_rps=100)
            with owned_processes(faults):
                try: w.validate(c, root / 'inputs/downstream', faults, options)
                finally:
                    if leftovers(faults, cleanup=True): raise RuntimeError('Validation left owned processes')
    load(root, require_frozen=False)
    b.save(root / 'validation.json', dict(complete=True, settings_sha256=b.digest(root / 'settings.json'),
        smoke_audit=b.read(smoke / 'audit.json'), faults=str(faults.relative_to(root)), faults_sha256=b.digest(faults / 'validation.json'),
        validator=dict(path=str(Path(__file__).resolve()), sha256=b.digest(Path(__file__))),
        unit_test_log_sha256=b.digest(validation / 'unit-tests.log'), finished_epoch=time.time()))
    print('VALIDATED', root, flush=True)


def report(root):
    settings, planned, state = b.read(root / 'settings.json'), b.read(root / 'plan.json'), b.read(root / 'state.json')
    rows = []
    for record in state['groups'].values():
        for filename in record.get('results', []):
            row = b.read(root / filename)
            rows.append(dict(row, path=filename, group_complete=record['status'] == 'complete',
                performance_exclusions=row['performance_exclusions'] +
                    ([record['invalidated_reason']] if record.get('invalidated_reason') else [])))
    summaries = []
    for case in dict.fromkeys(g['name'] for g in planned):
        for jdk in settings['jdks']:
            values = [row for row in rows if row['workload'] == case and row['jdk'] == jdk]
            if not values: continue
            group = dict(workload=case, jdk=jdk, editions={}, paired_nio_ratios={})
            eligible_by_role = {}
            for role in settings['roles']:
                observed = [row for row in values if row['role'] == role]
                eligible = [row for row in observed if row['group_complete'] and row['outcome'] == 'pass' and not row['performance_exclusions']]
                metrics = {}
                eligible_by_role[role] = {row['repeat']: row for row in eligible}
                for key in ('requests_per_second', 'payload_mib_per_second', 'background_rps', 'foreground_rps',
                            'p50_ms', 'p95_ms', 'p99_ms', 'p999_ms', 'p99_9_ms', 'foreground_p99_ms',
                            'scheduled_p99_ms', 'cpu_us_per_success', 'cpu_us_per_request', 'gc_millis',
                            'rss_peak_mib', 'cgroup_peak_mib', 'native_threads_peak'):
                    numbers = [r['measurement'][key] for r in eligible if r.get('measurement', {}).get(key) is not None]
                    if numbers: metrics[key] = dict(median=statistics.median(numbers), minimum=min(numbers), maximum=max(numbers))
                group['editions'][role] = dict(selected_trials=len(observed), eligible=len(eligible), metrics=metrics,
                    outcomes=[r['outcome'] for r in observed], results=[r['path'] for r in observed],
                    exclusions=[dict(path=r['path'], reasons=r['performance_exclusions']) for r in observed if r['performance_exclusions']])
            for reference in ('mu3', 'blocking'):
                ratios = {}
                nio, other = eligible_by_role.get('nio', {}), eligible_by_role.get(reference, {})
                for repeat in nio.keys() & other.keys():
                    a, z = nio[repeat]['measurement'], other[repeat]['measurement']
                    for key in group['editions']['nio']['metrics'].keys() & group['editions'][reference]['metrics'].keys():
                        if isinstance(a.get(key), (int, float)) and isinstance(z.get(key), (int, float)) and z[key] > 0:
                            ratios.setdefault(key, []).append(a[key] / z[key])
                group['paired_nio_ratios'][reference] = {key: dict(pairs=len(values), median=statistics.median(values),
                    minimum=min(values), maximum=max(values)) for key, values in ratios.items()}
            summaries.append(group)
    summary = dict(complete=state['complete'], profile=settings['profile'], planned_groups=len(planned),
        completed_groups=sum(r['status'] == 'complete' for r in state['groups'].values()),
        source_identities=settings['source_identities'], groups=summaries,
        qualification='Within-workload medians and ranges only. Busy/smoke/tuning/failed/rate-limited or generator-limited rows are excluded from speed rankings. Resource limits remain outcomes. Native request latency is not an open-loop SLA.')
    b.save(root / 'summary.json', summary)
    parts = ['<!doctype html><meta charset="utf-8"><title>Three-way full retest</title>',
        '<style>body{font:14px system-ui;margin:24px}table{border-collapse:collapse}th,td{padding:8px;border:1px solid #bbb}td{vertical-align:top}pre{white-space:pre-wrap}</style>',
        '<h1>Three-way full retest</h1>', '<p>' + html.escape(summary['qualification']) + '</p>',
        f'<p>{summary["completed_groups"]}/{len(planned)} comparison groups completed. Profile: {settings["profile"]}.</p>',
        '<p><a href="settings.json">Frozen settings</a> · <a href="plan.json">Exact matrix</a> · <a href="state.json">Checkpoints and attempts</a> · <a href="summary.json">Summary data</a> · <a href="audit.json">Audit</a></p>',
        '<table><tr><th>Workload / JDK</th>' + ''.join('<th>' + role + '</th>' for role in settings['roles']) + '</tr>']
    for group in summaries:
        parts.append('<tr><td>' + html.escape(group['workload']) + '<br>Java ' + str(group['jdk']) + '</td>')
        for role in settings['roles']:
            item = group['editions'][role]
            cells = [f'{item["eligible"]} eligible / {item["selected_trials"]} selected trials']
            for key, value in item['metrics'].items():
                cells.append(f'{key}: {value["median"]:.3f} [{value["minimum"]:.3f}, {value["maximum"]:.3f}]')
            cells.append(', '.join(item['outcomes']))
            cells.extend('; '.join(e['reasons']) for e in item['exclusions'])
            links = ' '.join(f'<a href="{html.escape(path)}">trial {i+1}</a>' for i, path in enumerate(item['results']))
            parts.append('<td>' + '<br>'.join(html.escape(cell) for cell in cells) + '<br>' + links + '</td>')
        parts.append('</tr>')
    parts.append('</table>')
    (root / 'report.html').write_text('\n'.join(parts))


def invalidate_session(args):
    root = args.out.resolve()
    load(root)
    with campaign_lock(root):
        state = b.read(root / 'state.json')
        if not any(s['id'] == args.session for s in state['sessions']): raise ValueError('Unknown session')
        for record in state['groups'].values():
            if record['session'] == args.session:
                record.update(status='invalidated', invalidated_reason=args.reason)
                b.save(root / record['directory'] / 'invalidation.json',
                       dict(session=args.session, reason=args.reason, epoch=time.time()))
        state['complete'] = False
        b.save(root / 'state.json', state)
        prior = root / 'audit.json'
        if prior.exists(): prior.rename(root / f'audit-before-invalidation-{time.time_ns()}.json')
        report(root)
    print('Session invalidated; resume with --retry-failed to replace its complete comparison groups. Old attempts are retained.')


def audit(root):
    settings, planned, c = load(root, require_frozen=False)
    state = b.read(root / 'state.json')
    if not state['complete']: raise RuntimeError('Campaign is incomplete; status/report remain available')
    checks, results = 0, []
    for group in planned:
        record = state['groups'].get(group['id'], {})
        if record.get('status') != 'complete' or len(record['results']) != len(group['roles']):
            raise AssertionError('Incomplete comparison group')
        observed = []
        for filename in record['results']:
            row = b.read(root / filename)
            observed.append(row['role'])
            if row['outcome'] not in ('pass', 'resource limit'): raise AssertionError('Unresolved trial failure')
            if row['source_sha256'] != settings['source_identities'][row['role']]['source_sha256']:
                raise AssertionError('Source identity mismatch')
            if (row['workload'], row['jdk'], row['repeat']) != (group['name'], group['jdk'], group['repeat']):
                raise AssertionError('Result does not match its planned workload')
            if row['outcome'] == 'pass' and d.shutdown_error(row.get('launch', {})):
                raise AssertionError('Unverified JVM exit')
            if row['outcome'] == 'pass':
                launch = row['launch']
                if launch.get('mode_check', row.get('mode_check')) != b.expected_mode(row['role'], group['jdk']):
                    raise AssertionError('Unverified worker/transport policy')
                limits = launch.get('verified_limits', {})
                if limits.get('max') != group.get('cap_mib', 1024) * 1048576 or limits.get('swap.max') != 0:
                    raise AssertionError('Whole-process memory limits not verified')
            if row['outcome'] == 'resource limit' and not row.get('resource_evidence'):
                raise AssertionError('Unsupported resource-limit classification')
            evidence = Path(row['evidence_directory'])
            if (evidence / 'requests.tsv').exists() and row['outcome'] == 'pass':
                audit_data = c.audit_requests(evidence / 'requests.tsv')
                if audit_data['successful_samples'] != row['measurement']['requests']:
                    raise AssertionError('Native successful responses did not reconcile')
            if group['engine'] == 'downstream' and row['outcome'] == 'pass':
                raw = b.read(evidence / 'background.json')
                slots = [s['slot'] for s in raw['samples']] + [s['slot'] for s in raw['misses']]
                if sorted(slots) != list(range(raw['total_offered'])) or any(s['status'] != 200 for s in raw['samples']):
                    raise AssertionError('Downstream load accounting failed')
                foreground = b.read(evidence / 'foreground.json')
                samples = foreground['samples']
                if len(samples) + foreground['missed'] != foreground['offered'] or len({s['slot'] for s in samples}) != len(samples):
                    raise AssertionError('Downstream foreground slots did not reconcile')
                start, end = foreground['epoch'], foreground['epoch'] + foreground['elapsed']
                completed = sum(start <= s['epoch'] + s['latency_ms'] / 1000 < end for s in raw['samples'])
                if completed != row['measurement']['background_requests'] or len(samples) != row['measurement']['foreground_requests']:
                    raise AssertionError('Downstream measured completions did not reconcile')
                if any(row['recovered_metrics'][key] for key in ('pending', 'waiting_handlers', 'active_responses')):
                    raise AssertionError('Downstream work did not settle')
            results.append(dict(path=filename, sha256=b.digest(root / filename), outcome=row['outcome']))
            checks += 1
        if observed != group['roles']: raise AssertionError('Comparison order changed')
    if leftovers(root): raise AssertionError('Campaign still owns live processes')
    result = dict(complete=True, outcome='pass', checked_trials=checks, results=results,
        auditor=dict(path=str(Path(__file__).resolve()), sha256=b.digest(Path(__file__))),
        settings_sha256=b.digest(root / 'settings.json'), plan_sha256=b.digest(root / 'plan.json'),
        audited_epoch=time.time(), qualification='Resource limits are completed observations, not successful speed comparisons')
    b.save(root / 'audit.json', result)
    print('AUDIT PASS', checks, 'trials', flush=True)
    return result


def options():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('prepare', 'validate', 'run', 'status', 'pause', 'report', 'audit', '_trial', 'list', 'invalidate-session'))
    p.add_argument('--out', type=Path)
    p.add_argument('--source-inputs', type=Path, default=DEFAULT_INPUTS)
    p.add_argument('--repository', type=Path, default=REPO)
    p.add_argument('--parent-campaign', type=Path, help=argparse.SUPPRESS)
    p.add_argument('--profile', choices=('smoke', 'full'), default='full')
    for name in ('warmup', 'duration', 'repeats'): p.add_argument('--' + name, type=int)
    p.add_argument('--jdks', type=int, choices=(21, 25), nargs='+', default=[21, 25])
    p.add_argument('--roles', choices=b.ROLES, nargs='+', default=list(b.ROLES))
    p.add_argument('--families', nargs='+', default=[])
    p.add_argument('--cases', nargs='+', default=[])
    p.add_argument('--host-state', choices=('quiet', 'busy'))
    p.add_argument('--max-hours', type=float, default=0, help='Finish the current comparison group, then checkpoint')
    p.add_argument('--max-groups', type=int, default=0)
    p.add_argument('--retry-failed', action='store_true')
    p.add_argument('--session', type=int, help='Session to invalidate after unexpected desktop use or other interference')
    p.add_argument('--reason', help='Reason for invalidating a session; retained with the original measurements')
    p.add_argument('--group', help=argparse.SUPPRESS)
    p.add_argument('--role', choices=b.ROLES, help=argparse.SUPPRESS)
    p.add_argument('--directory', type=Path, help=argparse.SUPPRESS)
    a = p.parse_args()
    if a.action != 'list' and a.out is None: p.error('--out is required')
    if a.action in ('run', '_trial') and not a.host_state: p.error('--host-state is required')
    if a.action == 'invalidate-session' and (a.session is None or not a.reason): p.error('--session and --reason are required')
    if a.max_groups < 0 or a.max_hours < 0: p.error('Session limits must not be negative')
    if len(set(a.jdks)) != len(a.jdks) or len(set(a.roles)) != len(a.roles): p.error('Duplicate JDK or edition')
    return a


def main():
    args = options()
    if args.action == 'list':
        for row in matrix.catalog(): print(row['family'], row['name'])
        return
    root = args.out.resolve()
    if args.action == 'prepare': prepare(args)
    elif args.action == '_trial': raise SystemExit(trial_worker(args))
    elif args.action == 'validate': validate(args)
    elif args.action == 'run': run(args)
    elif args.action == 'invalidate-session': invalidate_session(args)
    elif args.action == 'pause':
        (root / 'pause.request').write_text(str(time.time()))
        print('Pause requested; the current three-edition group will finish first')
    elif args.action == 'status':
        state = b.read(root / 'state.json')
        print(json.dumps(dict(complete=state['complete'], total_groups=len(b.read(root / 'plan.json')),
            completed=sum(r['status'] == 'complete' for r in state['groups'].values()),
            unfinished={k:v['status'] for k,v in state['groups'].items() if v['status'] != 'complete'}), indent=2))
    elif args.action == 'report': report(root)
    elif args.action == 'audit': audit(root)


if __name__ == '__main__': main()
