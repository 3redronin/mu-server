import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import baseline as b
import full_retest as f
import retest_matrix as m
import roundness as r


class MatrixTests(unittest.TestCase):
    def settings(self, **kw):
        return dict(dict(profile='full', warmup=20, duration=60, repeats=3,
                         jdks=[21, 25], roles=list(b.ROLES)), **kw)

    def test_all_families_and_balanced_triples(self):
        plan = m.plan(self.settings())
        self.assertEqual(560, len(plan))
        self.assertEqual(len(plan), len({g['id'] for g in plan}))
        self.assertEqual(1680, sum(len(g['roles']) for g in plan))
        for group in plan:
            self.assertEqual(set(b.ROLES), set(group['roles']))
        for case in m.catalog():
            if case['family'] == 'heap-tuning': continue
            selected = [g for g in plan if g['name'] == case['name'] and g['jdk'] == 21]
            for position in range(3):
                self.assertEqual(set(b.ROLES), {g['roles'][position] for g in selected})

    def test_tuning_precedes_confirmation_and_is_not_repeated(self):
        plan = m.plan(self.settings())
        tune = [i for i, g in enumerate(plan) if g['phase'] == 'tuning']
        confirm = [i for i, g in enumerate(plan) if g['family'] == 'heap-confirmation']
        self.assertEqual(32, len(tune))
        self.assertLess(max(tune), min(confirm))
        self.assertTrue(all(plan[i]['warmup'] == 5 and plan[i]['duration'] == 10 for i in tune))
        with self.assertRaises(ValueError): m.plan(self.settings(families=['heap-confirmation']))

    def test_downstream_and_fairness_controls_are_present(self):
        cases = m.catalog()
        downstream = [g for g in cases if g['family'] == 'downstream']
        self.assertEqual(12, len(downstream))
        self.assertEqual({0, 50, 250}, {g['delay_ms'] for g in downstream})
        for protocol in ('http1', 'https1'):
            self.assertEqual({0, 8}, {g['background_clients'] for g in cases if g['engine'] == 'writers' and g['protocol'] == protocol})
        self.assertEqual({(window, background) for window in (65535, 2097152) for background in (False, True)},
                         {(g['stream_window'], g['background']) for g in cases if g['engine'] == 'h2-writers'})

    def test_smoke_bounds_cohorts_and_bad_filters_fail(self):
        plan = m.plan(self.settings(profile='smoke', repeats=1))
        self.assertLessEqual(max(g.get('idle', 0) for g in plan), 64)
        self.assertLessEqual(max(g['paused'] for g in plan), 8)
        for kw in (dict(cases=['does-not-exist']), dict(families=['unknown'])):
            with self.assertRaises(ValueError): m.plan(self.settings(**kw))

    def test_burst_script_covers_long_warmup_and_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            r.burst_command(['h2load', '--rps', '32', 'https://localhost/raw'], directory, 8, seconds=85)
            lines = (directory / 'burst-timing.tsv').read_text().splitlines()
            self.assertEqual(340, len(lines))
            self.assertGreater(float(lines[-1].split()[0]), 84000)


class LifecycleTests(unittest.TestCase):
    def test_reused_pids_are_never_killed(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / 'owned.jsonl').write_text(json.dumps(dict(pid=1234, start_ticks='old', process_group=True)) + '\n')
            with patch.object(f, 'start_ticks', return_value='new'), patch.object(f.os, 'killpg') as kill:
                self.assertEqual([], f.leftovers(directory, cleanup=True))
                kill.assert_not_called()

    def test_unit_names_cannot_target_unowned_services(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            b.save(directory / 'launch.json', dict(unit='important-user-service.service'))
            with self.assertRaises(RuntimeError): f.leftovers(directory, cleanup=True)

    def test_busy_host_and_missing_validation_cannot_start_full_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            args = SimpleNamespace(out=Path(tmp), host_state='busy')
            with patch.object(f, 'load', return_value=(dict(profile='full'), [], None)):
                with self.assertRaises(ValueError): f.run(args)
                args.host_state = 'quiet'
                with self.assertRaises(RuntimeError): f.run(args)

    def test_resume_keeps_completed_groups_and_rotated_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            settings = dict(profile='smoke', lock_path=str(root / 'lock'))
            b.save(root / 'settings.json', settings)
            b.save(root / 'state.json', dict(groups={}, sessions=[], complete=False))
            plan = [dict(id='case1', engine='native', roles=['mu3', 'blocking', 'nio'], warmup=1, duration=1),
                    dict(id='case2', engine='native', roles=['blocking', 'nio', 'mu3'], warmup=1, duration=1)]
            invoked = []
            class Worker:
                pid = 99999999
                def __init__(self, command, **kwargs):
                    role = command[command.index('--role') + 1]
                    directory = Path(command[command.index('--directory') + 1])
                    directory.mkdir()
                    b.save(directory / 'result.json', dict(outcome='pass', role=role))
                    invoked.append(role)
                def wait(self, timeout): return 0
            args = SimpleNamespace(out=root, host_state='busy', max_hours=0, max_groups=1, retry_failed=False)
            with patch.object(f, 'load', return_value=(settings, plan, None)), patch.object(f, 'report'), \
                    patch.object(f, 'leftovers', return_value=[]), patch.object(f.subprocess, 'Popen', Worker), \
                    patch.object(f.shutil, 'disk_usage', return_value=SimpleNamespace(free=100 * 1024**3)):
                f.run(args)
                self.assertFalse(b.read(root / 'state.json')['complete'])
                f.run(args)
                self.assertTrue(b.read(root / 'state.json')['complete'])
                f.run(args)
            self.assertEqual(['mu3', 'blocking', 'nio', 'blocking', 'nio', 'mu3'], invoked)

    def test_generic_failure_cannot_be_called_resource_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            (path / 'server.log').write_text('Connection reset by peer')
            self.assertIsNone(f.resource_evidence(dict(outcome='failure'), path))
            (path / 'server.log').write_text('java.lang.OutOfMemoryError: Java heap space')
            self.assertEqual('OutOfMemoryError', f.resource_evidence(dict(outcome='failure'), path))
            self.assertIsNone(f.resource_evidence(dict(outcome='pass'), path))

    def test_invalidating_session_retains_evidence_and_requires_explicit_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            settings = dict(profile='smoke', lock_path=str(root / 'lock'))
            b.save(root / 'settings.json', settings)
            b.save(root / 'state.json', dict(complete=True, sessions=[dict(id=0)], groups={'g':
                dict(session=0, status='complete', directory='attempts/g/0', attempts=['attempts/g/0'], results=[])}))
            b.save(root / 'attempts/g/0/raw.json', dict(original=True))
            b.save(root / 'audit.json', dict(complete=True))
            args = SimpleNamespace(out=root, session=0, reason='Desktop work overlapped measurements',
                                   host_state='busy', max_hours=0, max_groups=0, retry_failed=False)
            with patch.object(f, 'load', return_value=(settings, [dict(id='g')], None)), patch.object(f, 'report'):
                f.invalidate_session(args)
                self.assertEqual('invalidated', b.read(root / 'state.json')['groups']['g']['status'])
                self.assertTrue(b.read(root / 'attempts/g/0/raw.json')['original'])
                self.assertEqual(args.reason, b.read(root / 'attempts/g/0/invalidation.json')['reason'])
                self.assertFalse((root / 'audit.json').exists())
                with patch.object(f.shutil, 'disk_usage', return_value=SimpleNamespace(free=100 * 1024**3)):
                    with self.assertRaisesRegex(RuntimeError, 'retry-failed'): f.run(args)

    def test_changed_frozen_code_fails_before_loading_harness(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'code').mkdir()
            script = root / 'code/full_retest.py'
            script.write_text('original')
            b.save(root / 'settings.json', dict(python=f.python_runtime(), code=f.code_inventory(root / 'code')))
            script.write_text('changed')
            with self.assertRaisesRegex(RuntimeError, 'Frozen controller changed'):
                f.load(root)

    def test_heap_selection_uses_zero_control_and_freezes_choice(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records = {}
            for cap in (512, 1024):
                for heap in (cap // 4, cap // 2, cap * 3 // 4, cap):
                    for idle in (0, 4096):
                        name = f'{cap}-{heap}-{idle}.json'
                        b.save(root / name, dict(family='heap-tuning', cap_mib=cap, jdk=21, role='nio',
                            heap=f'{heap}m', idle=idle, outcome='pass', target_rate_met=not (idle == 0 and heap == cap // 4)))
                        records[name] = dict(status='complete', results=[name])
            settings = dict(jdks=[21], roles=['nio'])
            selected = f.selected_heaps(root, dict(groups=records), settings)
            self.assertEqual('256m', selected['512/21/nio']['heap'])
            self.assertEqual('512m', selected['1024/21/nio']['heap'])
            self.assertEqual(selected, f.selected_heaps(root, dict(groups={}), settings))

    def test_report_keeps_native_paths_exclusions_and_only_pairs_same_repeat(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            b.save(root / 'settings.json', dict(profile='full', jdks=[21], roles=list(b.ROLES), source_identities={}))
            b.save(root / 'plan.json', [dict(name='small')])
            results = []
            for repeat in range(3):
                for role in b.ROLES:
                    filename = f'{repeat}-{role}.json'
                    b.save(root / filename, dict(path='../native/path', workload='small', jdk=21, role=role,
                        repeat=repeat, outcome='pass', performance_exclusions=['generator_busy'] if (repeat, role) == (1, 'mu3') else [],
                        measurement=dict(requests_per_second=200 if role == 'nio' else 100)))
                    results.append(filename)
            b.save(root / 'state.json', dict(complete=True, groups={'a': dict(status='complete', results=results)}))
            f.report(root)
            group = b.read(root / 'summary.json')['groups'][0]
            self.assertEqual(2, group['paired_nio_ratios']['mu3']['requests_per_second']['pairs'])
            self.assertEqual(2, group['paired_nio_ratios']['mu3']['requests_per_second']['median'])
            self.assertEqual(3, group['paired_nio_ratios']['blocking']['requests_per_second']['pairs'])
            self.assertEqual(['generator_busy'], group['editions']['mu3']['exclusions'][0]['reasons'])
            self.assertEqual('0-nio.json', group['editions']['nio']['results'][0])

    def test_auditor_accepts_verified_mode_in_result_or_launch_but_rejects_wrong_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            b.save(root / 'settings.json', {})
            b.save(root / 'plan.json', [])
            b.save(root / 'state.json', dict(complete=True, groups={'g': dict(status='complete', results=['result.json'])}))
            settings = dict(source_identities={'nio': dict(source_sha256='source')})
            planned = [dict(id='g', roles=['nio'], name='small', jdk=25, repeat=0, engine='shared')]
            row = dict(outcome='pass', role='nio', workload='small', jdk=25, repeat=0, source_sha256='source',
                       evidence_directory=str(root / 'evidence'),
                       launch=dict(verified_limits={'max':1024*1048576, 'swap.max':0}))
            with patch.object(f, 'load', return_value=(settings, planned, None)), \
                    patch.object(f.d, 'shutdown_error', return_value=None), patch.object(f, 'leftovers', return_value=[]):
                for placement in ('result', 'launch'):
                    target = row if placement == 'result' else row['launch']
                    target['mode_check'] = b.expected_mode('nio', 25)
                    b.save(root / 'result.json', row)
                    self.assertEqual(1, f.audit(root)['checked_trials'])
                    target['mode_check'] = 'wrong'
                    b.save(root / 'result.json', row)
                    with self.assertRaisesRegex(AssertionError, 'worker/transport'): f.audit(root)
                    target.pop('mode_check')


if __name__ == '__main__': unittest.main()
