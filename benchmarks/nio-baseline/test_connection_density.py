import io
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import connection_density as d


class DensityTests(unittest.TestCase):
    def test_heap_overrides_change_only_selected_editions_and_preserve_total_budget(self):
        heaps = d.heap_configuration('256m', ['21/nio=512m', '25/nio=384m'], 512)
        settings = dict(heap='256m', heap_by_edition=heaps, direct_memory_mib=512)
        self.assertEqual('512m', d.trial_heap(settings, dict(jdk=21, role='nio')))
        self.assertEqual('256m', d.trial_heap(settings, dict(jdk=21, role='mu3')))
        options = ['-Dcomparison.threading=VIRTUAL']
        self.assertEqual(options+['-XX:MaxDirectMemorySize=512m'], d.memory_options(settings, options))
        self.assertEqual(['-Dcomparison.threading=VIRTUAL'], options)
        self.assertEqual([], d.memory_options({}, []))
        for overrides in (['21/nio=768m'], ['21/nio=256m', '21/nio=384m'], ['25/unknown=128m']):
            with self.assertRaises(ValueError): d.heap_configuration('256m', overrides, 512)

    def test_heap_selection_uses_smallest_successful_heap_not_best_tuning_latency(self):
        def row(heap, rate, p99):
            return dict(jdk=25, role='nio', idle=4096, heap=heap, outcome='pass', target_rate_met=rate>=9800,
                        measurement=dict(requests_per_second=rate, p99_ms=p99))
        rows = [row('128m', 7000, 90), row('256m', 10000, 2), row('384m', 10000, .1)]
        self.assertEqual('256m', d.choose_heap(rows))
        rows[1]['generator_busy'] = True
        self.assertEqual('384m', d.choose_heap(rows))

    def test_failed_tuning_cannot_claim_target_success_or_hide_cohort_failure(self):
        rows = [dict(jdk=21, role='nio', idle=4096, heap=heap, outcome='Java heap exhaustion',
                     target_rate_met=False, launch=dict(idle_cohort=dict(established=count)))
                for heap,count in [('128m',1000), ('256m',2800), ('384m',4096), ('512m',4096)]]
        self.assertEqual('384m', d.choose_heap(rows))
        rows[1].update(outcome='pass', measurement=dict(requests_per_second=5000))
        self.assertEqual('256m', d.choose_heap(rows))
        rows[2].update(outcome='pass', measurement=dict(requests_per_second=7000))
        self.assertEqual('384m', d.choose_heap(rows))
        with self.assertRaises(ValueError): d.choose_heap([])
        rows[0]['jdk'] = 25
        with self.assertRaises(ValueError): d.choose_heap(rows)

    def test_early_failure_reaps_only_owned_generator_and_reader(self):
        generator = Mock(pid=123, returncode=-15, stdout=io.StringIO())
        generator.poll.return_value = None
        server = Mock(pid=456)
        reader = Mock(); reader.is_alive.return_value = False
        popen = Mock(side_effect=[server, generator]); threads = Mock(return_value=reader)
        c = SimpleNamespace(subprocess=SimpleNamespace(Popen=popen), threading=SimpleNamespace(Thread=threads))
        def read_generator(): pass
        def invoke():
            c.subprocess.Popen(['systemd-run', 'fixture'])
            c.subprocess.Popen(['taskset', '/native/h2load'], start_new_session=True)
            c.threading.Thread(target=read_generator)
            raise RuntimeError('stats failed before inner cleanup')
        with patch.object(d.os, 'killpg') as kill:
            with self.assertRaisesRegex(RuntimeError, 'stats failed'):
                d.native_with_cleanup(c, Path('/native/h2load'), invoke)
            kill.assert_called_once_with(123, d.signal.SIGTERM)
        generator.wait.assert_called_once_with(timeout=2)
        server.wait.assert_not_called(); reader.join.assert_called_once_with(timeout=3)
        self.assertTrue(generator.stdout.closed)
        self.assertIs(c.subprocess.Popen, popen); self.assertIs(c.threading.Thread, threads)

    def test_generator_without_owned_group_is_rejected_before_spawn(self):
        popen = Mock()
        c = SimpleNamespace(subprocess=SimpleNamespace(Popen=popen), threading=SimpleNamespace(Thread=Mock()))
        with self.assertRaisesRegex(AssertionError, 'own a process group'):
            d.native_with_cleanup(c, Path('/native/h2load'), lambda: c.subprocess.Popen(['/native/h2load']))
        popen.assert_not_called()

    def test_normal_generator_is_reaped_without_forced_cleanup(self):
        generator = Mock(pid=123, returncode=0, stdout=io.StringIO())
        generator.poll.return_value = 0
        c = SimpleNamespace(subprocess=SimpleNamespace(Popen=Mock(return_value=generator)),
                            threading=SimpleNamespace(Thread=Mock()))
        def invoke():
            c.subprocess.Popen(['/native/h2load'], start_new_session=True)
            return dict(outcome='pass')
        with patch.object(d.os, 'killpg') as kill:
            row = d.native_with_cleanup(c, Path('/native/h2load'), invoke)
            kill.assert_not_called()
        self.assertEqual('pass', row['outcome'])
        self.assertFalse(row['generator_lifecycle'][0]['forced'])
        self.assertEqual(0, row['generator_lifecycle'][0]['exit_code'])

    def test_unresponsive_generator_is_killed_and_success_cannot_hide_cleanup(self):
        generator = Mock(pid=123, returncode=-9, stdout=io.StringIO())
        generator.poll.return_value = None
        generator.wait.side_effect = [d.subprocess.TimeoutExpired('h2load', 2), -9]
        c = SimpleNamespace(subprocess=SimpleNamespace(Popen=Mock(return_value=generator)),
                            threading=SimpleNamespace(Thread=Mock()))
        def invoke():
            c.subprocess.Popen(['/native/h2load'], start_new_session=True)
            return dict(outcome='pass')
        with patch.object(d.os, 'killpg') as kill:
            row = d.native_with_cleanup(c, Path('/native/h2load'), invoke)
        self.assertEqual([d.signal.SIGTERM, d.signal.SIGKILL], [call.args[1] for call in kill.call_args_list])
        self.assertNotEqual('pass', row['outcome'])
        self.assertTrue(row['generator_lifecycle'][0]['forced'])

    def test_unfinished_log_reader_prevents_next_trial(self):
        thread = Mock(); thread.is_alive.return_value = True
        factory = Mock(return_value=thread)
        c = SimpleNamespace(subprocess=SimpleNamespace(Popen=Mock()), threading=SimpleNamespace(Thread=factory))
        def read_generator(): pass
        def invoke():
            c.threading.Thread(target=read_generator)
            return dict(outcome='pass')
        with self.assertRaisesRegex(RuntimeError, 'refusing another trial'):
            d.native_with_cleanup(c, Path('/native/h2load'), invoke)
        self.assertIs(c.threading.Thread, factory)

    def test_missed_target_keeps_observed_latency_and_gc_visible(self):
        row = dict(jdk=21, case=dict(protocol='https1'), idle=4096, role='nio', outcome='pass',
                   performance_exclusions=['target missed'], key='trial', peak_mib=497, measurement_peak_mib=486,
                   launch=dict(samples=[dict(phase='measurement', process=dict(os_threads=66))]),
                   measurement_before=dict(heap_bytes=10*1048576, buffer_pool_bytes=40*1048576),
                   measurement_after=dict(heap_bytes=20*1048576, buffer_pool_bytes=30*1048576),
                   measurement=dict(requests_per_second=7161.4, p99_ms=68.933, gc_millis=6254))
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run/'results.json').write_text(json.dumps(dict(spec={}, trials=[row], plan=[row], complete=True)))
            d.report(run)
            group = json.loads((run/'summary.json').read_text())['groups'][0]
            self.assertEqual(0, group['valid']); self.assertIsNone(group['requests_per_second'])
            self.assertEqual(row['measurement'], group['observed'])
            self.assertEqual(dict(heap_bytes=20, buffer_pool_bytes=40), group['boundary_mib'])
            self.assertIn('68.933', (run/'report.html').read_text())
            self.assertIn('6,254', (run/'report.html').read_text())

    def test_late_host_activity_excludes_timing_without_rewriting_raw_evidence(self):
        row=dict(jdk=25,case=dict(protocol='https1'),idle=4096,role='nio',outcome='pass',
                 performance_exclusions=[],key='trial',heap='512m',peak_mib=630,measurement_peak_mib=620,
                 launch=dict(samples=[dict(phase='measurement',process=dict(os_threads=30))]),
                 measurement=dict(requests_per_second=10000,p99_ms=.2,gc_millis=900))
        with tempfile.TemporaryDirectory() as directory:
            run=Path(directory);ledger=run/'results.json'
            ledger.write_text(json.dumps(dict(spec=dict(host_state='quiet'),trials=[row],plan=[row],complete=True)))
            original=ledger.read_bytes()
            (run/'post-audit-findings.json').write_text(json.dumps(dict(performance_exclusions=['Concurrent desktop use'])))
            d.report(run)
            summary=json.loads((run/'summary.json').read_text())['groups'][0]
            self.assertEqual(1,summary['correct']);self.assertEqual(0,summary['valid'])
            self.assertIsNone(summary['requests_per_second'])
            self.assertEqual(10000,summary['observed']['requests_per_second'])
            self.assertIn('INDICATIVE ONLY',(run/'report.html').read_text())
            self.assertEqual(original,ledger.read_bytes())

    def test_client_fd_limit_cannot_create_a_false_server_capacity_result(self):
        with patch.object(d.resource, 'getrlimit', return_value=(1024, 65536)), \
             patch.object(d.resource, 'setrlimit') as setter:
            limits = d.configure_descriptors(4096)
            setter.assert_called_once_with(d.resource.RLIMIT_NOFILE, (8192, 65536))
            self.assertEqual(1024, limits['soft_before'])
        with patch.object(d.resource, 'getrlimit', return_value=(-1, -1)), \
             patch.object(d.resource, 'setrlimit') as setter:
            self.assertEqual(-1, d.configure_descriptors(4096)['soft'])
            setter.assert_called_once_with(d.resource.RLIMIT_NOFILE, (-1, -1))
        with patch.object(d.resource, 'getrlimit', return_value=(1024, 2048)), \
             patch.object(d.resource, 'setrlimit') as setter:
            with self.assertRaises(ValueError): d.configure_descriptors(2048)
            setter.assert_not_called()

    def test_reader_consumes_exactly_one_fixed_or_chunked_response(self):
        fixed = b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello'
        chunked = b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3\r\nllo\r\n0\r\n\r\n'
        for first in (fixed, chunked):
            reader = io.BytesIO(first + fixed)
            d.response(reader, b'hello'); d.response(reader, b'hello')
            self.assertEqual(b'', reader.read())

    def test_rejection_closed_connection_and_wrong_payload_cannot_count_as_idle(self):
        for data in (
            b'HTTP/1.1 503 Unavailable\r\nContent-Length: 5\r\n\r\nhello',
            b'HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 5\r\n\r\nhello',
            b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhell',
            b'HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nwrong',
            b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n6\r\nhello!\r\n0\r\n\r\n',
        ):
            with self.subTest(data=data), self.assertRaises(AssertionError):
                d.response(io.BytesIO(data), b'hello')

    def test_partial_cohort_never_reports_configured_count_as_verified(self):
        peers = d.IdlePeers(1, 'http1', 2, b'hello')
        peers.peers = [(None, None)]
        with self.assertRaises(AssertionError): peers.verify_all()
        self.assertEqual(0, peers.resumed)

    def test_all_resumptions_are_checked_and_failures_are_not_hidden(self):
        peers = d.IdlePeers(1, 'http1', 2, b'hello')
        peers.peers = [('first', None), ('second', None)]
        def probe(sock, reader):
            if sock == 'second': raise AssertionError('disconnected')
        peers.probe = probe
        with self.assertRaises(AssertionError): peers.verify_all()
        self.assertEqual(1, peers.resumed)

    def test_launcher_success_does_not_prove_jvm_success(self):
        self.assertIsNotNone(d.shutdown_error(dict(launcher_exit_code=0,
            unit_status=dict(MainPID='22', Result='success', ExecMainCode='0', ExecMainStatus='0'))))
        self.assertIsNotNone(d.shutdown_error(dict(forced_cleanup_stop=True,
            unit_status=dict(MainPID='0', Result='exit-code', ExecMainCode='1', ExecMainStatus='143'))))
        self.assertIsNone(d.shutdown_error(dict(unit_status=dict(MainPID='0', Result='success',
            ExecMainCode='1', ExecMainStatus='0'))))

    def test_plan_matches_load_and_rotates_all_editions(self):
        rows = d.plan([0, 1024, 4096], ['http1', 'https1'], [21, 25], 3, 10000)
        self.assertEqual(108, len(rows)); self.assertEqual(108, len({r['key'] for r in rows}))
        for jdk in (21, 25):
            for protocol in ('http1', 'https1'):
                for count in (0, 1024, 4096):
                    triples = [[r for r in rows if r['jdk'] == jdk and r['case']['protocol'] == protocol
                                and r['idle'] == count and r['repeat'] == repeat] for repeat in range(3)]
                    for triple in triples:
                        self.assertEqual(set(d.b.ROLES), {r['role'] for r in triple})
                        self.assertTrue(all(r['case'] == triple[0]['case'] and r['target_rps'] == 10000
                                            and r['paused'] == 0 for r in triple))
                    for position in range(3):
                        self.assertEqual(set(d.b.ROLES), {triple[position]['role'] for triple in triples})


if __name__ == '__main__': unittest.main()
