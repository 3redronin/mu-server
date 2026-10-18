import tempfile
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import Mock, patch

from h2.events import DataReceived, ResponseReceived, StreamEnded, StreamReset, ConnectionTerminated

import baseline as b
import roundness as r


class RoundedTests(TestCase):
    def peer(self, credit=0):
        peer = r.SharedConnection.__new__(r.SharedConnection)
        peer.peer = Mock()
        peer.paused = [1]
        peer.streams = {1:dict(body=bytearray(),ended=False,headers=None,size=8388608,credit=credit)}
        return peer

    def test_zero_credit_cannot_silently_become_completed_or_data_bearing(self):
        for event in (DataReceived(stream_id=1,data=b'a',flow_controlled_length=1),StreamEnded(stream_id=1)):
            with self.assertRaises(AssertionError): self.peer().events([event])

    def test_resumption_replenishes_connection_credit_only(self):
        peer = self.peer(1)
        peer.events([DataReceived(stream_id=1,data=b'a',flow_controlled_length=1)])
        self.assertEqual(b'a',peer.streams[1]['body'])
        peer.peer.increment_flow_control_window.assert_called_once_with(1)

    def test_rejections_and_remote_cancellation_fail_probe(self):
        for event in (ResponseReceived(stream_id=1,headers=[(':status','503')]),
                      StreamReset(stream_id=1,error_code=8),ConnectionTerminated()):
            with self.assertRaises(AssertionError): self.peer().events([event])

    def test_window_override_is_scoped_and_preserves_other_arguments(self):
        tool = Path('/tool/h2load')
        original = ['taskset',str(tool),'-w','16','-W','24','--rps','32']
        changed = r.native_command(original,tool,10)
        self.assertEqual(['taskset',str(tool),'-w','10','-W','24','--rps','32'],changed)
        self.assertEqual('16',original[3])
        unrelated = ['java','-w','16']
        self.assertIs(unrelated,r.native_command(unrelated,tool,10))
        with self.assertRaises(AssertionError): r.native_command([str(tool),'-w','12'],tool,10)

    def test_paired_loads_and_rotated_editions(self):
        for suite,count in [('multiplex',4),('fairness',2)]:
            rows = r.plan(suite,[21,25],3)
            self.assertEqual(count*18,len(rows))
            self.assertEqual(len(rows),len({row['key'] for row in rows}))
            for jdk in (21,25):
                for workload,*_ in r.cases(suite):
                    triples = [[row for row in rows if row['jdk']==jdk and row['workload']==workload and row['repeat']==i] for i in range(3)]
                    for position in range(3):
                        self.assertEqual(set(b.ROLES),{triple[position]['role'] for triple in triples})
                    self.assertEqual(1,len({row['target_rps'] for triple in triples for row in triple}))
        native = r.cases('multiplex')
        self.assertEqual(native[0][1:4],native[1][1:4])
        self.assertEqual(native[2][2],native[3][2])
        self.assertEqual(native[2][1]['clients']*native[2][1]['streams'],native[3][1]['clients']*native[3][1]['streams'])

    def test_bursts_match_total_rate_across_topologies_and_measure_actual_overlap(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            for clients in (1,8):
                command = r.burst_command(['h2load','--rps','32','https://localhost/raw'],path,clients)
                self.assertNotIn('--rps',command)
                lines = (path/'burst-timing.tsv').read_text().splitlines()
                self.assertEqual(256//clients,len(lines))
                self.assertEqual(8//clients,sum(line.startswith('100\t') for line in lines))
                self.assertEqual('https://localhost/raw',command[-1])
            log = path/'requests.tsv'
            log.write_text('100\t200\t100\n150\t200\t100\n200\t200\t100\n')
            self.assertEqual(2,r.overlap(log))
            log.write_text('100\t200\t50\n150\t200\t50\n200\t200\t50\n')
            self.assertEqual(1,r.overlap(log))

    def test_failed_exit_and_target_miss_remain_visible(self):
        row = dict(outcome='pass',target_rps=200,measurement=dict(requests_per_second=150))
        r.finish_row(row)
        self.assertEqual('cleanup failure',row['outcome'])
        self.assertFalse(row['target_rate_met'])
        self.assertIn('PC in use; external contention uncontrolled',row['performance_exclusions'])
        self.assertIn('Target foreground rate was not achieved',row['performance_exclusions'])

    def test_slow_foreground_does_not_trigger_catchup_bursts(self):
        now = [0.0]
        def slow_request():
            now[0] += .6
            return .6
        peer = Mock(request=slow_request)
        with patch.object(r.time,'monotonic',side_effect=lambda:now[0]), \
             patch.object(r.time,'sleep',side_effect=lambda seconds:now.__setitem__(0,now[0]+seconds)):
            result = r.paced_requests(peer,1,10)
        self.assertEqual([0,.6],[s['offset_seconds'] for s in result['samples']])
        self.assertEqual(1.2,result['elapsed_seconds'])

    def test_failed_rows_cannot_become_valid_performance_in_report(self):
        item = r.plan('fairness',[21],1)[0]
        row = dict(item,outcome='failure',target_rate_met=False,measurement=dict(requests_per_second=100,p99_ms=55),
                   measurement_peak_mib=100,native_threads=20)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            b.save(path/'results.json',dict(plan=[item],trials=[row],complete=True))
            r.report(path)
            summary = b.read(path/'summary.json')['groups'][0]
            self.assertEqual(0,summary['correct'])
            self.assertEqual(0,summary['valid_performance'])
            self.assertEqual(55,summary['p99_ms'])
            self.assertIn('INDICATIVE ONLY',(path/'report.html').read_text())


if __name__ == '__main__': main()
