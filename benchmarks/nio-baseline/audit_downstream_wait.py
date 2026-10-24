#!/usr/bin/env python3
"""Reconcile a completed downstream comparison with its raw samples and owned processes."""
import argparse
import json
from pathlib import Path
import subprocess

import baseline as b
import connection_density as d
from downstream_wait import plan as expected_plan


def audit(inputs, comparison, validation):
    checks, units, children = [], set(), set()

    def require(condition, message):
        if not condition:
            raise AssertionError(message)
        checks.append(message)

    def identities(directory):
        settings = b.read(directory / 'settings.json')
        require(settings['manifest_sha256'] == b.digest(inputs / 'manifest.json'), str(directory) + ': manifest unchanged')
        for index, (filename, expected) in enumerate(settings['controller_sources'].items()):
            require(b.digest(Path(filename)) == expected, filename + ': controller unchanged')
            require(b.digest(directory / (str(index) + '-' + Path(filename).name)) == expected,
                    filename + ': executed copy matches')
        return settings

    def cleanup(row, name, injected=False):
        launch = row.get('launch')
        if launch:
            units.add(launch['unit'])
            require(launch['unit_status']['MainPID'] == '0', name + ': JVM stopped')
            if not injected:
                require(d.shutdown_error(launch) is None, name + ': normal JVM exit')
        for key in ('generator_lifecycle', 'dependency_lifecycle'):
            if key not in row:
                continue
            child = row[key]
            children.add(child['pid'])
            require(child['exit_code'] is not None and not child['reader_alive'], name + ': child reaped and reader joined')
            try:
                command_now = Path('/proc', str(child['pid']), 'cmdline').read_bytes().split(b'\0')
            except FileNotFoundError:
                command_now = []
            # A later audit may encounter a reused PID. Match the unique output
            # directory in the recorded command rather than blaming an unrelated process.
            output = child['command'][child['command'].index('--output') + 1].encode()
            require(output not in command_now, name + ': owned child no longer exists')
            if not injected:
                require(child['exit_code'] == 0 and not child['forced'] and not child.get('stop_error'), name + ': normal child exit')

    def slots(raw, name):
        all_slots = [s['slot'] for s in raw['samples']] + [s['slot'] for s in raw['misses']]
        require(sorted(all_slots) == list(range(raw['total_offered'])), name + ': every offered slot accounted once')
        require(all(s['status'] == 200 for s in raw['samples']), name + ': every loaded body validated')
        require(raw['peak_active'] <= 256, name + ': bounded load concurrency')

    c = b.load_harness(inputs / 'harness')
    manifest = b.verify_manifest(inputs, c)
    current = c.process.identity(Path(__file__).resolve().parents[2])
    require(current['source_sha256'] == manifest['provenance']['nio']['source_sha256'], 'NIO production matches checkout')
    settings = identities(comparison)
    identities(validation)
    result = b.read(comparison / 'results.json')
    require(result['complete'], 'comparison complete')
    plan = b.read(comparison / 'plan.json')
    require(plan == expected_plan(settings['jdks'], settings['repeats'], settings['delays'],
                                  settings['protocols'], settings['roles']), 'saved plan matches requested matrix')
    rows = result['trials']
    require([r['key'] for r in rows] == [r['key'] for r in plan], 'all planned trials completed in order')
    foreground_count = background_count = 0
    for row in rows:
        name = row['key']
        directory = comparison / 'trials' / name
        require(row['outcome'] == 'pass', name + ': successful trial')
        require(b.read(directory / 'result.json')['measurement'] == row['measurement'], name + ': saved result matches ledger')
        require(row['mode_check'] == b.expected_mode(row['role'], row['jdk']), name + ': worker/transport policy verified')
        require(not row['connections_incomplete'], name + ': complete connection population')
        require(row['measurement']['errors'] == 0, name + ': no downstream errors')
        require(all(row['recovered_metrics'][k] == 0 for k in ('pending', 'waiting_handlers', 'active_responses')),
                name + ': no pending work after recovery')
        require(row['launch']['verified_limits']['max'] == 1024 * 1048576
                and row['launch']['verified_limits']['swap.max'] == 0, name + ': whole-process memory cap verified')
        raw = b.read(directory / 'background.json')
        slots(raw, name)
        foreground = b.read(directory / 'foreground.json')
        require(len(foreground['samples']) + foreground['missed'] == foreground['offered'], name + ': foreground offered slots reconcile')
        require(len({s['slot'] for s in foreground['samples']}) == len(foreground['samples']), name + ': foreground slots unique')
        start, end = foreground['epoch'], foreground['epoch'] + foreground['elapsed']
        completed = sum(start <= s['epoch'] + s['latency_ms'] / 1000 < end for s in raw['samples'])
        require(completed == row['measurement']['background_requests'], name + ': background overlap count reconciles')
        require(len(foreground['samples']) == row['measurement']['foreground_requests'], name + ': foreground count reconciles')
        foreground_count += len(foreground['samples'])
        background_count += completed
        service = b.read(directory.with_name(directory.name + '-dependency') / 'downstream.json')
        require(not service['failures'] and service['stats']['active'] == 0 and service['stats']['connections'] == 0,
                name + ': downstream service drained without errors')
        cleanup(row, name)
    for row in result['calibration']:
        name = 'calibration-' + str(row['delay_ms'])
        require(row['outcome'] == 'pass', name + ': calibration completed correctly')
        slots(b.read(comparison / 'calibration' / ('delay-' + str(row['delay_ms'])) / 'background.json'), name)
        cleanup(row, name)
    validated = b.read(validation / 'validation.json')
    require(validated['complete'], 'failure validation complete')
    expected_validation = {(jdk, role, protocol, style) for jdk in settings['jdks'] for role in settings['roles']
                           for protocol in settings['protocols'] for style in ('blocking', 'async')}
    require({(r['jdk'], r['role'], r['protocol'], r['style']) for r in validated['trials']} == expected_validation,
            'all runtime/edition/protocol/style combinations validated')
    for row in validated['trials']:
        name = f"validation-{row['jdk']}-{row['role']}-{row['protocol']}-{row['style']}"
        require(row['outcome'] == 'pass', name + ': failure/recovery checks passed')
        require('body_timeout and recovery' in row['checks'], name + ': stalled-body deadline verified')
        state = row['final_metrics']
        require(all(state[k] == 0 for k in ('pending', 'waiting_handlers', 'active_responses')), name + ': shutdown work settled')
        require(state['started'] == sum(state[k] for k in ('downstream_success', 'failures', 'timeouts', 'cancellations')),
                name + ': outbound completions accounted once')
        cleanup(row, name)
    require(len(validated['injected_failures']) == 2, 'both failure injection phases exercised')
    for index, row in enumerate(validated['injected_failures']):
        require(row['outcome'] == 'failure' and 'injected' in row['error'], 'injected failure cannot pass')
        cleanup(row, 'injected-' + str(index), injected=True)
    # systemd may already have garbage-collected stopped transient units. Require
    # an explicit inactive record for each requested ID even when show returns nonzero.
    statuses = subprocess.run(['systemctl', '--user', 'show', *sorted(units), '--property=Id,MainPID,ActiveState'],
                              text=True, capture_output=True, timeout=30)
    observed = set()
    for block in statuses.stdout.strip().split('\n\n'):
        values = dict(line.split('=', 1) for line in block.splitlines())
        require(values.get('MainPID', '0') == '0' and values['ActiveState'] == 'inactive', values['Id'] + ': unit inactive now')
        observed.add(values['Id'])
    require(observed == units, 'all owned JVM unit states observed')
    return dict(outcome='pass', checks=len(checks), detail=checks, trials=len(rows), validated_combinations=len(validated['trials']),
                measured_foreground_responses=foreground_count, measured_background_responses=background_count,
                owned_jvm_units=len(units), reaped_children=len(children), current_production_sha256=current['source_sha256'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('inputs', 'comparison', 'validation', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.inputs.resolve(), args.comparison.resolve(), args.validation.resolve())
    b.save(args.out, result)
    print(json.dumps({key: value for key, value in result.items() if key != 'detail'}))
