import json
from pathlib import Path
import tempfile
import unittest

import baseline as b


class BaselineTests(unittest.TestCase):
    def test_busy_host_can_never_supply_comparison_evidence(self):
        row = dict(outcome="pass", measurement=dict(requests_per_second=999999))
        self.assertTrue(b.exclusions(row, "busy", "full"))
        self.assertTrue(b.exclusions(row, "quiet", "smoke"))
        self.assertEqual([], b.exclusions(row, "quiet", "full"))

    def test_failures_and_generator_limits_remain_excluded_on_quiet_host(self):
        for field in (dict(outcome="fail"), dict(generator_busy=True),
                      dict(connections_incomplete=True), dict(target_rate_met=False)):
            row = dict(outcome="pass")
            row.update(field)
            self.assertTrue(b.exclusions(row, "quiet", "full"))

    def test_plan_pairs_same_workload_budget_and_worker_policy(self):
        rows = b.plan("smoke", 8, ["https1", "h2"], 1)
        self.assertEqual(60, len(rows))
        self.assertEqual(60, len({row["key"] for row in rows}))
        for jdk in (21, 25):
            for role in b.ROLES:
                selected = [r for r in rows if r["jdk"] == jdk and r["role"] == role]
                self.assertEqual(10, len(selected))
                for protocol in ("https1", "h2"):
                    alone, paused = [r for r in selected if r["case"]["id"] == "isolation-" + protocol]
                    self.assertEqual(alone["case"], paused["case"])
                    self.assertEqual(alone["target_rps"], paused["target_rps"])
                    self.assertEqual([0, 8], [alone["paused"], paused["paused"]])
        self.assertEqual("PLATFORM:false:SOCKET", b.expected_mode("blocking", 21))
        self.assertEqual("PLATFORM:false:NIO", b.expected_mode("nio", 21))
        self.assertEqual("VIRTUAL:true:SOCKET", b.expected_mode("blocking", 25))
        self.assertEqual("VIRTUAL:true:NIO", b.expected_mode("nio", 25))
        self.assertEqual("AUTO:false:NETTY", b.expected_mode("mu3", 25))

    def test_full_repeats_rotate_each_edition_through_each_position(self):
        rows = b.plan("full", 128, ["https1"], 3)
        for jdk in (21, 25):
            groups = [[r["role"] for r in rows if r["jdk"] == jdk and r["repeat"] == repeat
                       and r["workload"] == "small"] for repeat in range(3)]
            for position in range(3):
                self.assertEqual(set(b.ROLES), {group[position] for group in groups})

    def test_foreground_connections_cannot_replace_a_missing_paused_peer(self):
        b.require_peers({1, 2}, {1, 2, 3, 4})
        with self.assertRaises(AssertionError):
            b.require_peers({1, 2}, {1, 3, 4, 5})

    def test_rejected_or_completed_responses_are_not_paused_clients(self):
        b.check_paused_headers("200", str(b.PAUSED_BYTES), 100)
        for args in (("503", str(b.PAUSED_BYTES), 0), ("200", "128", 0),
                     ("200", str(b.PAUSED_BYTES), b.PAUSED_BYTES)):
            with self.assertRaises(AssertionError):
                b.check_paused_headers(*args)

    def test_idle_tcp_is_insufficient_to_claim_stalled_output(self):
        peers = b.PausedPeers(1, "https1", 2)
        with self.assertRaises(AssertionError):
            peers.verify_pending(dict(send_queue_bytes={"10": 120, "11": 0}))
        self.assertEqual(2, peers.verify_pending(dict(send_queue_bytes={"10": 120, "11": 40}))["verified"])

    def test_cpu_ranges(self):
        self.assertEqual({0, 1, 8, 9}, b.cpu_set("0-1,8-9"))
        for invalid in ("", "2-1", "-1", "1-2-3"):
            with self.assertRaises(ValueError):
                b.cpu_set(invalid)

    def test_report_keeps_busy_warning_failures_and_source_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "runs/busy"
            run.mkdir(parents=True)
            row = dict(jdk=21, workload="small", repeat=0, role="nio", path="runs/busy/trials/0/21/small/nio",
                       outcome="<failed>", measurement=dict(requests_per_second=900000),
                       performance_exclusions=["PC in use"])
            spec = dict(host_state="busy", profile="smoke", server_cpus="0", generator_cpus="1",
                        warmup=1, duration=3, repeats=1, paused_connections=8)
            b.save(run / "results.json", dict(spec=spec, trials=[row], plan=[row], complete=False))
            b.report(run)
            text = (run / "report.html").read_text()
            self.assertIn("not to rank implementations", text)
            self.assertIn("&lt;failed&gt;", text)
            self.assertNotIn("<failed>", text)
            self.assertIn('href="trials/0/21/small/nio/result.json"', text)
            self.assertIn('href="../../manifest.json"', text)


if __name__ == "__main__":
    unittest.main()
