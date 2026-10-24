#!/usr/bin/env python3
"""Timer-based loopback HTTP/1 dependency for downstream_wait.py; no worker per request."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import signal
import time
from urllib.parse import parse_qs, urlsplit


class Service:
    def __init__(self, payload):
        if len(payload) != 128:
            raise ValueError('Downstream payload must be 128 bytes')
        self.payload = payload
        self.active = self.peak = self.completed = self.disconnected = 0
        self.samples = []
        self.connections = set()

    def stats(self):
        return dict(active=self.active, peak_active=self.peak, completed=self.completed,
                    disconnected=self.disconnected, connections=len(self.connections),
                    samples=len(self.samples), epoch=time.time())

    async def connection(self, reader, writer):
        self.connections.add(writer)
        try:
            while True:
                header = await reader.readuntil(b'\r\n\r\n')
                method, target, version = header.split(b'\r\n', 1)[0].decode('ascii').split()
                if method != 'GET' or version != 'HTTP/1.1':
                    raise ValueError('Only non-pipelined HTTP/1.1 GET is supported')
                query = parse_qs(urlsplit(target).query)
                fault = query.get('fault', [''])[0]
                delay_ms = 3000 if fault in ('timeout', 'body_timeout') else int(query.get('delay', ['0'])[0])
                if not 0 <= delay_ms <= 10000:
                    raise ValueError('Invalid delay')
                self.active += 1
                self.peak = max(self.peak, self.active)
                start = time.monotonic()
                sample = dict(epoch=time.time(), delay_ms=delay_ms, outcome='disconnected')
                disconnected = asyncio.create_task(reader.read(1))
                try:
                    if fault == 'body_timeout':
                        writer.write(b'HTTP/1.1 200 Test\r\nContent-Length: 128\r\n\r\n')
                        await writer.drain()
                    # A pending read detects cancellation during the timer. The JDK HTTP/1
                    # client never pipelines; data before the response is a harness error.
                    done, _ = await asyncio.wait([disconnected], timeout=delay_ms / 1000)
                    now = time.monotonic()
                    sample.update(actual_delay_ms=(now - start) * 1000,
                                  scheduling_lateness_ms=max(0, (now - start) * 1000 - delay_ms))
                    if done:
                        if disconnected.result():
                            raise ValueError('Unexpected downstream pipelining')
                        break
                    if fault == 'disconnect':
                        break
                    body = b'wrong' if fault == 'body' else self.payload
                    status = 503 if fault == 'status' else 200
                    header = b'' if fault == 'body_timeout' else f'HTTP/1.1 {status} Test\r\nContent-Length: {len(body)}\r\n\r\n'.encode()
                    writer.write(header + body)
                    await writer.drain()
                    sample.update(outcome='complete', status=status)
                    self.completed += 1
                finally:
                    disconnected.cancel()
                    await asyncio.gather(disconnected, return_exceptions=True)
                    self.active -= 1
                    if sample['outcome'] == 'disconnected':
                        self.disconnected += 1
                    self.samples.append(sample)
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        finally:
            self.connections.discard(writer)
            writer.close()
            try:
                await writer.wait_closed()
            except ConnectionError:
                pass


async def serve(args):
    service = Service(args.payload.read_bytes()[:128])
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    failures = []
    tasks = set()

    def connected(reader, writer):
        task = asyncio.create_task(service.connection(reader, writer))
        tasks.add(task)

        def completed(done):
            tasks.discard(done)
            if not done.cancelled() and done.exception() is not None:
                failures.append(repr(done.exception()))
        task.add_done_callback(completed)

    server = await asyncio.start_server(connected, '127.0.0.1', 0, limit=16384, backlog=1024)
    print('READY', os.getpid(), server.sockets[0].getsockname()[1], flush=True)
    control = asyncio.StreamReader()
    transport, _ = await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(control), os.fdopen(os.dup(0), 'rb'))

    async def commands():
        while line := await control.readline():
            command = line.strip()
            if command == b'STATS':
                print('STATS', json.dumps(service.stats()), flush=True)
            elif command == b'STOP':
                stop.set()
                return
            else:
                raise ValueError('Unknown service command')
        stop.set()

    command_task = asyncio.create_task(commands())
    try:
        await stop.wait()
    finally:
        server.close()
        await server.wait_closed()
        transport.close()
        command_task.cancel()
        await asyncio.gather(command_task, return_exceptions=True)
        remaining = list(tasks)
        for task in remaining:
            task.cancel()
        await asyncio.gather(*remaining, return_exceptions=True)
        args.output.write_text(json.dumps(dict(stats=service.stats(), samples=service.samples, failures=failures)))
    if failures:
        raise RuntimeError(failures)
    print('STOPPED', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    asyncio.run(serve(parser.parse_args()))
