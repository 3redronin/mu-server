import asyncio
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import downstream_service as service
import downstream_wait as w
import downstream_load as load


class PlanTests(unittest.TestCase):
    def test_default_matrix_and_balanced_editions(self):
        rows = w.plan()
        self.assertEqual(48, len(rows))
        self.assertEqual(48, len({r['key'] for r in rows}))
        for index in range(0, len(rows), 3):
            self.assertEqual(set(w.b.ROLES), {r['role'] for r in rows[index:index + 3]})
        self.assertEqual({('http1', 0), ('http1', 250), ('h2', 250)},
                         {(r['protocol'], r['delay_ms']) for r in rows if r['style'] != 'direct'})

    def test_optional_delay_and_subsets(self):
        rows = w.plan((25,), 1, (50,), ('h2',), ('nio',))
        self.assertEqual(3, len(rows))
        self.assertEqual({'direct', 'blocking', 'async'}, {r['style'] for r in rows})
        self.assertTrue(all(r['jdk'] == 25 and r['role'] == 'nio' for r in rows))

    def test_worker_policy(self):
        for jdk, role, expected in [(21, 'mu3', 'AUTO'), (25, 'mu3', 'AUTO'),
                                    (21, 'nio', 'PLATFORM'), (25, 'blocking', 'VIRTUAL')]:
            self.assertIn('-Dcomparison.threading=' + expected,
                          w.fixture_options(dict(jdk=jdk, role=role, style='async'), 'http://localhost'))

    def test_load_capacity_is_equal_and_rate_is_global(self):
        c = type('Harness', (), dict(GENERATOR_CPUS='0'))
        for protocol, clients, streams in [('http1', 256, 1), ('h2', 8, 32)]:
            command = w.load_command(c, Path('/inputs'), Path('/out'), 1234, protocol, 250, 400, 3, 5)
            self.assertEqual((clients, streams), w.shape(protocol))
            self.assertEqual(400, int(command[command.index('--rate') + 1]))


class PacingTests(unittest.TestCase):
    def test_stalls_count_missing_slots_without_catchup(self):
        now = [0.0]
        reply = dict(status=200, body_size=128, body_sha256='expected')
        class Probe:
            def request(self, path):
                now[0] += .25
                return reply
        with patch.object(w.time, 'monotonic', lambda: now[0]), patch.object(w.time, 'time', lambda: 1000), \
                patch.object(w.time, 'sleep', lambda value: now.__setitem__(0, now[0] + value)):
            result = w.paced(Probe(), 'expected', 1, 10)
        self.assertEqual(4, len(result['samples']))
        self.assertEqual(6, result['missed'])
        self.assertEqual([0, 2, 5, 7], [s['slot'] for s in result['samples']])
        self.assertEqual(10, result['offered'])
        self.assertEqual(1, result['elapsed'])

    def test_corrupted_response_is_not_a_latency_sample(self):
        class Probe:
            def request(self, path):
                return dict(status=200, body_size=128, body_sha256='wrong')
        with self.assertRaises(AssertionError):
            w.paced(Probe(), 'expected', 1, 10)

    def test_empty_percentile_is_not_zero_latency(self):
        self.assertIsNone(w.percentile([]))


class ChildTests(unittest.TestCase):
    def test_graceful_child_reaped_and_reader_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            child = w.Child([sys.executable, '-u', '-c',
                             'print("READY"); input(); print("STOPPED")'], Path(tmp) / 'log')
            try:
                child.line('READY')
            finally:
                child.close(graceful=True)
            self.assertFalse(child.record['forced'])
            self.assertEqual(0, child.record['exit_code'])
            self.assertFalse(child.record['reader_alive'])
            self.assertFalse(Path('/proc', str(child.process.pid)).exists())

    def test_startup_exit_and_measurement_exception_reap_owned_children(self):
        for script in ['raise SystemExit(2)', 'import time; print("READY", flush=True); time.sleep(60)']:
            with self.subTest(script=script), tempfile.TemporaryDirectory() as tmp:
                child = w.Child([sys.executable, '-u', '-c', script], Path(tmp) / 'log')
                row = dict(outcome='failure')
                try:
                    if 'SystemExit' in script:
                        with self.assertRaises(RuntimeError):
                            child.line('READY')
                    else:
                        child.line('READY')
                finally:
                    w.finish_cleanup(row, None, child, None)
                self.assertIsNotNone(child.process.returncode)
                self.assertFalse(child.reader.is_alive())
                self.assertFalse(Path('/proc', str(child.process.pid)).exists())

    def test_forced_cleanup_cannot_count_as_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            child = w.Child([sys.executable, '-u', '-c', 'import time; print("READY"); time.sleep(60)'], Path(tmp) / 'log')
            child.line('READY')
            row = dict(outcome='pass')
            w.finish_cleanup(row, None, child, None)
            self.assertEqual('cleanup failure', row['outcome'])

    def test_cleanup_continues_after_an_independent_failure(self):
        calls = []
        class Broken:
            record = {}
            def close(self):
                calls.append('fixture')
                raise RuntimeError('stop failed')
        class Good:
            record = dict(forced=False, exit_code=0)
            def close(self, graceful):
                calls.append('dependency')
        row = dict(outcome='pass')
        w.finish_cleanup(row, Broken(), None, Good())
        self.assertEqual(['fixture', 'dependency'], calls)
        self.assertEqual('cleanup failure', row['outcome'])


class BackendTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.backend = service.Service(b'x' * 128)
        self.reader = asyncio.StreamReader()
        self.writes = []
        writes = self.writes
        class Writer:
            def write(self, data):
                writes.append(data)
            async def drain(self):
                pass
            def close(self):
                pass
            async def wait_closed(self):
                pass
        self.task = asyncio.create_task(self.backend.connection(self.reader, Writer()))

    async def asyncTearDown(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)

    def send(self, query):
        self.reader.feed_data(f'GET /download?{query} HTTP/1.1\r\nHost: localhost\r\n\r\n'.encode())

    async def wait_for(self, predicate):
        async def wait():
            while not predicate():
                await asyncio.sleep(.001)
        await asyncio.wait_for(wait(), 2)

    async def test_timer_delay_keepalive_and_exact_body(self):
        for index in range(2):
            self.send('delay=20')
            await self.wait_for(lambda: len(self.writes) == index + 1)
            self.assertTrue(self.writes[-1].endswith(b'x' * 128))
        self.reader.feed_eof()
        await self.task
        self.assertEqual(2, self.backend.completed)
        self.assertEqual(0, self.backend.active)
        self.assertTrue(all(s['actual_delay_ms'] >= 20 for s in self.backend.samples))

    async def test_disconnect_cancels_timer_and_releases_active_count(self):
        self.send('delay=1000')
        await self.wait_for(lambda: self.backend.active == 1)
        self.reader.feed_eof()
        await asyncio.wait_for(self.task, .5)
        self.assertEqual(0, self.backend.active)
        self.assertEqual(1, self.backend.disconnected)
        self.assertEqual([], self.writes)

    async def test_shutdown_cancels_pending_work(self):
        self.send('delay=1000')
        await self.wait_for(lambda: self.backend.active == 1)
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        self.assertEqual(0, self.backend.active)
        self.assertEqual(0, len(self.backend.connections))

    async def test_body_timeout_sends_headers_before_stalling(self):
        self.send('delay=0&fault=body_timeout')
        await self.wait_for(lambda: len(self.writes) == 1)
        self.assertEqual(b'HTTP/1.1 200 Test\r\nContent-Length: 128\r\n\r\n', self.writes[0])
        self.reader.feed_eof()
        await asyncio.wait_for(self.task, .5)
        self.assertEqual(0, self.backend.active)
        self.assertEqual(1, self.backend.disconnected)

    async def test_fault_status_and_body_are_real_protocol_responses(self):
        for fault, expected in [('status', b'HTTP/1.1 503'), ('body', b'wrong')]:
            before = len(self.writes)
            self.send('delay=0&fault=' + fault)
            await self.wait_for(lambda: len(self.writes) > before)
            self.assertIn(expected, self.writes[-1])


class LoadTests(unittest.IsolatedAsyncioTestCase):
    async def test_capacity_is_bounded_and_every_slot_is_accounted_for(self):
        class SlowPeer:
            async def request(self):
                await asyncio.sleep(.08)
        result = await load.schedule([SlowPeer(), SlowPeer()], 1, 500, 0, .04)
        self.assertEqual(20, len(result['samples']) + len(result['misses']))
        self.assertLessEqual(result['peak_active'], 2)
        self.assertTrue(any(s['reason'] == 'capacity' for s in result['misses']))
        self.assertTrue(all(s['status'] == 200 for s in result['samples']))

    async def test_request_errors_are_retained(self):
        class BrokenPeer:
            async def request(self):
                raise ValueError('wrong body')
        result = await load.schedule([BrokenPeer()], 1, 100, 0, .02)
        self.assertTrue(result['samples'])
        self.assertTrue(all(s['status'] == 0 and 'wrong body' in s['error'] for s in result['samples']))

    async def test_http1_rejects_invalid_body_and_framing(self):
        class Writer:
            def write(self, data):
                pass
            async def drain(self):
                pass
        for reply in [b'HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\ny',
                      b'HTTP/1.1 503 Error\r\nContent-Length: 1\r\n\r\nx',
                      b'HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx',
                      b'HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx']:
            reader = asyncio.StreamReader()
            reader.feed_data(reply); reader.feed_eof()
            peer = load.Peer(reader, Writer(), b'x', '/download', False)
            with self.subTest(reply=reply), self.assertRaises(ValueError):
                await peer.request()

    async def test_http2_multiplexes_and_checks_every_body(self):
        from h2.config import H2Configuration
        from h2.connection import H2Connection
        from h2.events import RequestReceived
        for body in (b'x' * 128, b'wrong'):
            reader = asyncio.StreamReader()
            server = H2Connection(config=H2Configuration(client_side=False, header_encoding='utf-8'))
            server.initiate_connection()
            reader.feed_data(server.data_to_send())

            class Writer:
                def write(self, data):
                    for event in server.receive_data(data):
                        if isinstance(event, RequestReceived):
                            server.send_headers(event.stream_id, [(':status', '200'), ('content-length', str(len(body)))])
                            server.send_data(event.stream_id, body, end_stream=True)
                    data = server.data_to_send()
                    if data:
                        reader.feed_data(data)
                async def drain(self):
                    pass
                def close(self):
                    reader.feed_eof()
                async def wait_closed(self):
                    pass
            peer = load.Peer(reader, Writer(), b'x' * 128, '/download', True)
            try:
                results = await asyncio.gather(*(peer.request() for _ in range(32)), return_exceptions=True)
                if body == b'x' * 128:
                    self.assertEqual([None] * 32, results)
                else:
                    self.assertTrue(all(isinstance(result, ValueError) for result in results))
                self.assertEqual({}, peer.pending)
            finally:
                await peer.close()


if __name__ == '__main__':
    unittest.main()
