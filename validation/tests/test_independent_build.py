"""Opt-in real build/runtime identity regressions; use an isolated Mu4 checkout."""
import os,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from muvalidate import process

class RuntimeIdentityTests(unittest.TestCase):
    def test_wrong_major_override_is_rejected(self):
        actual=process.jdk_path(21)
        with patch.dict(os.environ,{'MU_JAVA_11':str(actual)}):
            with self.assertRaisesRegex(RuntimeError,'11.*21'):
                process.jdk_path(11)

@unittest.skipUnless(os.environ.get('MU_CONFORMANCE_TEST_CHECKOUT'),'requires isolated checkout for real Maven rebuild')
class BuildIdentityTests(unittest.TestCase):
    def test_rebuild_removes_deleted_server_and_fixture_output(self):
        checkout=Path(os.environ['MU_CONFORMANCE_TEST_CHECKOUT']).resolve()
        with tempfile.TemporaryDirectory() as t, patch.object(process,'TARGET',Path(t)):
            server_output=checkout/'target/classes'; server_output.mkdir(parents=True,exist_ok=True)
            fixture_output=Path(t)/'build/mu4/classes'; fixture_output.mkdir(parents=True)
            stale=[server_output/'RemovedFromPreviousCommit.class',server_output/'obsolete-resource.txt',fixture_output/'RemovedFixture.class']
            for p in stale:p.write_bytes(b'obsolete build artifact')
            process.build('mu4',checkout,process.jdk_path(21))
            self.assertEqual([], [str(p) for p in stale if p.exists()])
