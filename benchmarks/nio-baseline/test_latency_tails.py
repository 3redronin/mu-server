import json
from pathlib import Path
import tempfile
import unittest
from latency_tails import analyze, tail_percentiles


class TailTests(unittest.TestCase):
    def fixture(self, run, completed):
        row=dict(key='trial',jdk=25,role='nio',idle=4096,outcome='pass',performance_exclusions=[],
                 measurement=dict(latency_sample_count=1))
        (run/'results.json').write_text(json.dumps(dict(complete=True,trials=[row])))
        trial=run/'trials/trial';trial.mkdir(parents=True)
        (trial/'h2load.json').write_text(json.dumps(dict(measurements=dict(requests=dict(done=completed),
            performance=dict(request=dict(samples=[.001],max=.001))))))

    def test_native_completion_count_detects_a_partial_sample_reservoir(self):
        with tempfile.TemporaryDirectory() as directory:
            run=Path(directory);self.fixture(run,completed=1000)
            with self.assertRaisesRegex(ValueError,'incomplete'):analyze(run)

    def test_post_audit_host_qualification_also_applies_to_deeper_tails(self):
        with tempfile.TemporaryDirectory() as directory:
            run=Path(directory);self.fixture(run,completed=1)
            original=(run/'results.json').read_bytes()
            (run/'post-audit-findings.json').write_text(json.dumps(dict(performance_exclusions=['busy'])))
            overlay=analyze(run)
            self.assertEqual(0,overlay['groups'][0]['valid'])
            self.assertEqual(1,overlay['groups'][0]['max_ms'])
            self.assertEqual([],overlay['rows'][0]['original_performance_exclusions'])
            self.assertEqual(['busy'],overlay['rows'][0]['performance_exclusions'])
            self.assertEqual(original,(run/'results.json').read_bytes())

    def test_rare_long_pauses_cannot_hide_behind_good_p99(self):
        values=[.0001]*99800+[.075]*200
        tails=tail_percentiles(dict(samples=values,max=.075),100000)
        self.assertEqual(.1,tails['p99_ms'])
        self.assertEqual(75,tails['p999_ms'])
        self.assertEqual(75,tails['p9999_ms'])
        self.assertEqual(75,tails['max_ms'])

    def test_nearest_rank_and_seconds_to_milliseconds(self):
        values=[n/1000 for n in range(1,1001)]
        tails=tail_percentiles(dict(samples=values,max=1),1000)
        self.assertEqual(990,tails['p99_ms'])
        self.assertEqual(999,tails['p999_ms'])
        self.assertEqual(1000,tails['p9999_ms'])

    def test_incomplete_unsorted_or_invalid_samples_cannot_claim_exact_tails(self):
        for samples,count,maximum in [([],0,0),([.1],2,.1),([.2,.1],2,.2),
                                      ([float('nan')],1,0),([-.1],1,-.1),([.1],1,.2)]:
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                tail_percentiles(dict(samples=samples,max=maximum),count)


if __name__=='__main__':unittest.main()
