#!/usr/bin/env python3
"""Bounded globally paced load; every response body is checked, and missed slots stay missed."""
import argparse
import asyncio
import json
from pathlib import Path
import ssl
import sys
import time


class Peer:
    def __init__(self, reader, writer, expected, path, h2):
        self.reader, self.writer, self.expected, self.path = reader, writer, expected, path
        self.h2 = h2
        self.pending = {}
        self.failure = None
        self.reader_task = None
        if h2:
            from h2.config import H2Configuration
            from h2.connection import H2Connection
            self.connection = H2Connection(config=H2Configuration(client_side=True, header_encoding='utf-8'))
            self.connection.initiate_connection()
            writer.write(self.connection.data_to_send())
            self.reader_task = asyncio.create_task(self.read_h2())

    async def read_h2(self):
        from h2.events import DataReceived, ResponseReceived, StreamEnded, StreamReset, ConnectionTerminated
        try:
            while True:
                data = await self.reader.read(65536)
                if not data:
                    raise EOFError('HTTP/2 connection closed')
                for event in self.connection.receive_data(data):
                    if isinstance(event, ConnectionTerminated):
                        raise RuntimeError('GOAWAY: ' + str(event.error_code))
                    entry = self.pending.get(getattr(event, 'stream_id', None))
                    if isinstance(event, DataReceived):
                        self.connection.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
                    if entry is None:
                        continue
                    future, body = entry
                    if isinstance(event, ResponseReceived) and dict(event.headers).get(':status') != '200':
                        raise ValueError('Non-200 HTTP/2 response')
                    if isinstance(event, DataReceived):
                        body.extend(event.data)
                        if len(body) > len(self.expected):
                            raise ValueError('Oversized HTTP/2 body')
                    if isinstance(event, StreamReset):
                        raise RuntimeError('RST_STREAM: ' + str(event.error_code))
                    if isinstance(event, StreamEnded) and not future.done():
                        if body != self.expected:
                            raise ValueError('Incorrect HTTP/2 body')
                        future.set_result(None)
                self.writer.write(self.connection.data_to_send())
                await self.writer.drain()
        except Exception as error:
            self.failure = error
            for future, _ in self.pending.values():
                if not future.done():
                    future.set_exception(error)

    async def request(self):
        if self.h2:
            if self.failure:
                raise self.failure
            stream = self.connection.get_next_available_stream_id()
            future = asyncio.get_running_loop().create_future()
            self.pending[stream] = future, bytearray()
            try:
                self.connection.send_headers(stream, [(':method', 'GET'), (':scheme', 'https'),
                    (':authority', 'localhost'), (':path', self.path)], end_stream=True)
                self.writer.write(self.connection.data_to_send())
                await self.writer.drain()
                await future
            finally:
                self.pending.pop(stream, None)
        else:
            self.writer.write(f'GET {self.path} HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: identity\r\n\r\n'.encode())
            await self.writer.drain()
            header = await self.reader.readuntil(b'\r\n\r\n')
            lines = header[:-4].split(b'\r\n')
            fields = {}
            for line in lines[1:]:
                name, value = line.split(b':', 1)
                name = name.lower()
                if name in fields:
                    raise ValueError('Duplicate response header')
                fields[name] = value.strip().lower()
            if lines[0].split()[1:2] != [b'200'] or fields.get(b'content-length') != str(len(self.expected)).encode():
                raise ValueError('Incorrect HTTP/1 status or length')
            if b'transfer-encoding' in fields or fields.get(b'connection') == b'close':
                raise ValueError('Unexpected response framing or connection close')
            if await self.reader.readexactly(len(self.expected)) != self.expected:
                raise ValueError('Incorrect HTTP/1 body')

    async def close(self):
        if self.reader_task:
            self.reader_task.cancel()
            await asyncio.gather(self.reader_task, return_exceptions=True)
        self.writer.close()
        try:
            await asyncio.wait_for(self.writer.wait_closed(), 2)
        except (OSError, asyncio.TimeoutError):
            pass


def shape(protocol):
    return (256, 1) if protocol == 'http1' else (8, 32)


async def schedule(peers, streams, rate, warmup, duration):
    available = asyncio.Queue()
    for _ in range(streams):
        for peer in peers:
            available.put_nowait(peer)
    observations, misses, running = [], [], set()
    start, epoch, cpu = time.monotonic(), time.time(), time.process_time()
    measurement_cpu = None
    count, index = int((warmup + duration) * rate), 0
    peak = 0

    async def once(peer, slot, due, phase):
        sent, sent_epoch = time.monotonic(), time.time()
        observation = dict(slot=slot, epoch=sent_epoch, phase=phase, dispatch_lateness_ms=(sent - due) * 1000)
        try:
            await asyncio.wait_for(peer.request(), 5)
            observation['status'] = 200
        except Exception as error:
            observation.update(status=0, error=repr(error))
        finally:
            observation['latency_ms'] = (time.monotonic() - sent) * 1000
            observations.append(observation)
            available.put_nowait(peer)

    try:
        while index < count:
            due = start + index / rate
            await asyncio.sleep(max(0, due - time.monotonic()))
            now = time.monotonic()
            current = min(count, int((now - start) * rate))
            # No catch-up bursts, and never queue work behind exhausted concurrency.
            for missed in range(index, current):
                misses.append(dict(slot=missed, reason='scheduler', phase='warmup' if missed / rate < warmup else 'measurement'))
            index = max(index, current)
            if index >= count:
                break
            phase = 'warmup' if index / rate < warmup else 'measurement'
            if phase == 'measurement' and measurement_cpu is None:
                measurement_cpu = time.process_time()
                print('MEASUREMENT', flush=True)
            if available.empty():
                misses.append(dict(slot=index, reason='capacity', phase=phase))
            else:
                task = asyncio.create_task(once(available.get_nowait(), index, start + index / rate, phase))
                running.add(task)
                task.add_done_callback(running.discard)
                peak = max(peak, len(running))
            index += 1
        await asyncio.sleep(max(0, start + warmup + duration - time.monotonic()))
        measured_cpu = time.process_time() - (measurement_cpu if measurement_cpu is not None else cpu)
        print('DRAINING', flush=True)
        if running:
            await asyncio.wait_for(asyncio.gather(*running), 6)
        return dict(samples=observations, misses=misses, epoch=epoch, warmup=warmup, duration=duration,
                    rate=rate, peak_active=peak, measurement_cpu_cores=measured_cpu / duration,
                    measurement_offered=int(duration * rate), total_offered=count,
                    cpu_seconds=time.process_time() - cpu)
    finally:
        remaining = list(running)
        for task in remaining:
            task.cancel()
        await asyncio.gather(*remaining, return_exceptions=True)


async def run(args):
    expected = args.payload.read_bytes()[:128]
    peers = []
    context = None
    if args.protocol == 'h2':
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False; context.verify_mode = ssl.CERT_NONE
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        context.set_alpn_protocols(['h2'])
    clients, streams = shape(args.protocol)

    async def connect():
        reader, writer = await asyncio.wait_for(asyncio.open_connection('127.0.0.1', args.port,
            ssl=context, server_hostname='localhost' if context else None, limit=16384), 5)
        try:
            if context and writer.get_extra_info('ssl_object').selected_alpn_protocol() != 'h2':
                raise ValueError('HTTP/2 ALPN missing')
            peer = Peer(reader, writer, expected, args.path, args.protocol == 'h2')
            peers.append(peer)
        except BaseException:
            writer.close()
            await writer.wait_closed()
            raise

    try:
        # Retain partial peers on setup failure and close every one in finally.
        results = await asyncio.gather(*(connect() for _ in range(clients)), return_exceptions=True)
        failures = [repr(r) for r in results if isinstance(r, BaseException)]
        if failures:
            raise RuntimeError(failures)
        print('READY', len(peers), flush=True)
        if (await asyncio.to_thread(sys.stdin.readline)).strip() != 'START':
            raise ValueError('Expected START')
        print('STARTED', flush=True)
        result = await schedule(peers, streams, args.rate, args.warmup, args.duration)
        result.update(connections=clients, streams_per_connection=streams)
        args.output.write_text(json.dumps(result))
        if any(s['status'] != 200 for s in result['samples']):
            raise RuntimeError('Background response failures; see raw samples')
    finally:
        await asyncio.gather(*(peer.close() for peer in peers))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--protocol', choices=('http1', 'h2'), required=True)
    parser.add_argument('--path', required=True)
    parser.add_argument('--rate', type=int, required=True)
    parser.add_argument('--warmup', type=int, required=True)
    parser.add_argument('--duration', type=int, required=True)
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    asyncio.run(run(parser.parse_args()))
