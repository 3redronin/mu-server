import json,tempfile,unittest
from pathlib import Path
from independent.runner import parse_h2spec,parse_autobahn,summary

class IndependentReports(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def autobahn(self,behavior='OK',close='OK'):
        d={'id':'1.1.1','agent':'mu4','behavior':behavior,'behaviorClose':close}
        (self.root/'case.json').write_text(json.dumps(d))
        (self.root/'index.json').write_text(json.dumps({'mu4':{'1.1.1':dict(d,reportfile='case.json')}}))
    def test_autobahn_requires_complete_inventory(self):
        self.autobahn()
        with self.assertRaisesRegex(ValueError,'coverage mismatch'):parse_autobahn(self.root,'mu4',['1.1.1','1.1.2'])
    def test_autobahn_requires_both_verdicts(self):
        self.autobahn(close=None)
        with self.assertRaisesRegex(ValueError,'Missing/inconsistent'):parse_autobahn(self.root,'mu4',['1.1.1'])
    def test_autobahn_checks_detail_against_index(self):
        self.autobahn();(self.root/'case.json').write_text(json.dumps({'id':'1.1.1','agent':'mu3'}))
        with self.assertRaisesRegex(ValueError,'identity'):parse_autobahn(self.root,'mu4',['1.1.1'])
    def test_non_strict_is_review_and_close_failure_is_failure(self):
        for behavior,close,expected in [('OK','OK','pass'),('OK','NON-STRICT','review'),('INFORMATIONAL','OK','review'),('OK','FAILED','fail')]:
            self.autobahn(behavior,close);self.assertEqual(parse_autobahn(self.root,'mu4',['1.1.1'])[0]['state'],expected)
    def test_h2spec_detects_empty_failure_elements_and_retains_name(self):
        p=self.root/'h2.xml';p.write_text('<testsuites><testsuite package="http2/1"><testcase classname="bad"><failure message="bad frame"/></testcase></testsuite></testsuites>')
        row=parse_h2spec(p,expected=1)[0];self.assertEqual(row['state'],'fail');self.assertEqual(row['description'],'bad');self.assertEqual(row['details'][0]['message'],'bad frame')
    def test_h2spec_truncated_report_cannot_pass(self):
        p=self.root/'h2.xml';p.write_text('<testsuites/>')
        with self.assertRaisesRegex(ValueError,'Expected 147'):parse_h2spec(p)
    def test_partial_matrix_and_reference_failure_handling(self):
        meta={'candidate':'mu4','servers':['mu3','mu4'],'jdks':[21],'tools':['h2spec']}
        candidate={'server':'mu4','jdk':21,'tool':'h2spec','state':'pass','cases':[{'id':'1','state':'pass'}]}
        reference=dict(candidate,server='mu3',state='fail',cases=[{'id':'1','state':'fail'}])
        self.assertEqual(summary(self.root,[candidate],meta),2)
        self.assertEqual(summary(self.root,[reference,candidate],meta),0)
        self.assertEqual(summary(self.root,[dict(reference,state='incomplete'),candidate],meta),2)
        self.assertEqual(summary(self.root,[reference,dict(candidate,state='review')],meta),1)
