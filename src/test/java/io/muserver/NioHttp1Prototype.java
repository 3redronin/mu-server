package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Disposable plaintext experiment, deliberately confined to tests. It uses the real Mu decoder
 * but a small raw-response handler, not Mu's handler/lifecycle APIs. See NONBLOCKING-IO-INVESTIGATION.md.
 */
final class NioHttp1Prototype implements AutoCloseable {
    static final int BUFFER_SIZE = 8192;
    private static final int TURN_LIMIT = 64;

    @FunctionalInterface
    interface Handler {
        void handle(HttpRequestTemp request, InputStream body, OutputStream response) throws Exception;
    }

    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Set<Connection> connections = new HashSet<>(); // loop owned
    private final ConcurrentLinkedQueue<Connection> commands = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor workers;
    private final Handler handler;
    private final int maxConnections;
    private final long idleNanos;
    final Thread loop;
    final AtomicInteger connectionCount = new AtomicInteger();
    final AtomicInteger readPauses = new AtomicInteger();
    final AtomicInteger bodyHighWater = new AtomicInteger();
    final AtomicInteger outputHighWater = new AtomicInteger();
    final AtomicInteger zeroWrites = new AtomicInteger();
    final AtomicInteger workerThreads = new AtomicInteger();
    volatile Throwable loopFailure;
    private volatile boolean running = true;

    NioHttp1Prototype(int maxConnections, int workerCount, Duration idleTimeout, Handler handler) throws IOException {
        if (maxConnections < 1 || workerCount < 1 || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("Positive connection, worker and idle limits required");
        }
        this.handler = handler;
        this.maxConnections = maxConnections;
        this.idleNanos = idleTimeout.toNanos();
        selector = Selector.open();
        try { listener = ServerSocketChannel.open(); }
        catch (IOException | RuntimeException failure) { selector.close(); throw failure; }
        workers = new ThreadPoolExecutor(workerCount, workerCount, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(maxConnections), task -> {
                Thread thread = new Thread(task, "nio-prototype-handler-" + workerThreads.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        try {
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException failure) {
            listener.close();
            selector.close();
            workers.shutdownNow();
            throw failure;
        }
        loop = new Thread(this::run, "nio-prototype-loop");
        loop.setDaemon(true);
        loop.start();
    }

    InetSocketAddress address() throws IOException {
        return (InetSocketAddress) listener.getLocalAddress();
    }

    private void run() {
        try {
            while (running) {
                for (int i = 0; i < TURN_LIMIT; i++) {
                    Connection connection = commands.poll();
                    if (connection == null) break;
                    connection.scheduled.set(false);
                    connection.drive(false, true);
                }
                if (commands.isEmpty()) selector.select(50);
                else selector.selectNow();
                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext() && running) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    if (!key.isValid()) continue;
                    if (key.isAcceptable()) accept();
                    else ((Connection) key.attachment()).drive(key.isReadable(), key.isWritable());
                }
                long now = System.nanoTime();
                for (Connection connection : connections.toArray(new Connection[0])) {
                    if (now - connection.lastProgress >= idleNanos) connection.close(new IOException("Idle deadline"));
                }
            }
        } catch (Throwable failure) {
            loopFailure = failure;
        } finally {
            running = false;
            for (Connection connection : connections.toArray(new Connection[0])) connection.close(new IOException("Server stopped"));
            commands.clear();
            try { listener.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
        }
    }

    private void accept() throws IOException {
        for (int i = 0; i < TURN_LIMIT; i++) {
            SocketChannel channel = listener.accept();
            if (channel == null) return;
            if (connections.size() == maxConnections) {
                channel.close();
                continue;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(StandardSocketOptions.SO_SNDBUF, BUFFER_SIZE);
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                Connection connection = new Connection(channel, key);
                key.attach(connection);
                connections.add(connection);
                connectionCount.incrementAndGet();
            } catch (IOException | RuntimeException failure) {
                channel.close();
            }
        }
    }

    private final class Connection {
        final SocketChannel channel;
        final SelectionKey key;
        final ByteBuffer input = ByteBuffer.allocate(BUFFER_SIZE).flip();
        final ArrayDeque<HttpRequestTemp> requests = new ArrayDeque<>();
        final Http1MessageDecoder decoder = new Http1MessageDecoder(HttpMessageType.REQUEST, requests, BUFFER_SIZE, BUFFER_SIZE);
        final AtomicBoolean scheduled = new AtomicBoolean();
        final Pipe output = new Pipe(BUFFER_SIZE, this::schedule, outputHighWater);
        volatile boolean closed;
        long lastProgress = System.nanoTime();
        boolean peerEof;
        boolean eofDelivered;
        boolean paused;
        Exchange exchange;

        Connection(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
        }

        void schedule() {
            // Coalesce consumption/output notifications to one queued command per connection.
            if (!closed && scheduled.compareAndSet(false, true)) {
                commands.add(this);
                selector.wakeup();
            }
        }

        boolean canDecode() {
            return exchange == null || (!exchange.bodyComplete && !exchange.handlerDone && exchange.body.available() < BUFFER_SIZE);
        }

        void drive(boolean readable, boolean writable) {
            if (closed) return;
            try {
                if (readable && canDecode() && !peerEof) {
                    input.compact();
                    int count = channel.read(input);
                    input.flip();
                    if (count < 0) peerEof = true;
                    else if (count > 0) lastProgress = System.nanoTime();
                }
                int events = 0;
                while (input.hasRemaining() && canDecode() && events++ < TURN_LIMIT) {
                    int limit = input.limit();
                    if (exchange != null) input.limit(input.position() + Math.min(input.remaining(), BUFFER_SIZE - exchange.body.available()));
                    Http1ConnectionMsg event;
                    try { event = decoder.decode(input); }
                    finally { input.limit(limit); }
                    if (event instanceof HttpRequestTemp) {
                        HttpRequestTemp request = (HttpRequestTemp) event;
                        requests.remove(request);
                        // Unsupported prototype features fail closed; no claim of production conformance.
                        if (request.getRejectRequest() != null || request.isWebsocketUpgrade()
                            || request.headers().get(HeaderNames.EXPECT) != null) throw new IOException("Unsupported prototype request");
                        Exchange next = new Exchange(request);
                        exchange = next;
                        workers.execute(next::handle);
                    } else if (event instanceof MessageBodyBit) {
                        MessageBodyBit body = (MessageBodyBit) event;
                        Exchange current = Objects.requireNonNull(exchange);
                        current.body.offer(body.bytes(), body.offset(), body.length());
                        if (body.isLast()) {
                            current.bodyComplete = true;
                            current.body.finish();
                        }
                    }
                }
                if (input.hasRemaining() && canDecode()) schedule();
                if (peerEof && !input.hasRemaining() && !eofDelivered) {
                    decoder.endOfInput();
                    eofDelivered = true;
                }
                if (writable && output.available() > 0) {
                    int count = output.drain(channel, BUFFER_SIZE);
                    if (count == 0) zeroWrites.incrementAndGet();
                    else lastProgress = System.nanoTime();
                }
                if (exchange != null && exchange.handlerDone) {
                    if (exchange.failure != null) throw new IOException("Handler failed", exchange.failure);
                    if (output.available() == 0) {
                        boolean close = !exchange.bodyComplete || exchange.closeAfterResponse || peerEof;
                        exchange.body.fail(new IOException("Exchange finished"));
                        exchange = null;
                        if (close) { close(new IOException("Response complete")); return; }
                        schedule(); // Parse already-buffered pipelined bytes without waiting for another read event.
                    }
                }
                if (peerEof && exchange == null) { close(new IOException("Input ended")); return; }
                boolean read = !peerEof && canDecode();
                if (!read && !paused) readPauses.incrementAndGet();
                paused = !read;
                key.interestOps((read ? SelectionKey.OP_READ : 0) | (output.available() > 0 ? SelectionKey.OP_WRITE : 0));
            } catch (Exception failure) {
                close(new IOException("Connection ended", failure));
            }
        }

        void close(IOException failure) {
            if (closed) return;
            closed = true;
            key.cancel();
            try { channel.close(); } catch (IOException ignored) { }
            output.fail(failure);
            if (exchange != null) exchange.body.fail(failure);
            connections.remove(this);
            connectionCount.decrementAndGet();
        }

        private final class Exchange {
            final HttpRequestTemp request;
            final Pipe body = new Pipe(BUFFER_SIZE, Connection.this::schedule, bodyHighWater);
            final boolean closeAfterResponse;
            boolean bodyComplete; // loop owned
            volatile boolean handlerDone;
            Throwable failure; // published by handlerDone

            Exchange(HttpRequestTemp request) {
                this.request = request;
                closeAfterResponse = request.headers().closeConnectionRequested(Objects.requireNonNull(request.getHttpVersion()));
                bodyComplete = BodySize.NONE.equals(request.getBodySize());
                if (bodyComplete) body.finish();
            }

            void handle() {
                try {
                    handler.handle(request, new InputStream() {
                        @Override public int read() throws IOException {
                            byte[] one = new byte[1];
                            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            return body.read(bytes, offset, length);
                        }
                    }, new OutputStream() {
                        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                        @Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(bytes, offset, length); }
                        @Override public void flush() throws IOException { output.awaitDrained(); }
                    });
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    handlerDone = true;
                    schedule();
                }
            }
        }
    }

    /** Fixed storage in each direction: the loop never waits for capacity or data. */
    static final class Pipe {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final byte[] bytes;
        private final Runnable wakeup;
        private final AtomicInteger highWater;
        private int head;
        private int size;
        private boolean finished;
        private IOException failure;

        Pipe(int capacity, Runnable wakeup, AtomicInteger highWater) {
            bytes = new byte[capacity];
            this.wakeup = wakeup;
            this.highWater = highWater;
        }

        int available() {
            lock.lock();
            try { return size; } finally { lock.unlock(); }
        }

        void offer(byte[] source, int offset, int length) throws IOException {
            lock.lock();
            try {
                checkFailure();
                if (finished || length > bytes.length - size) throw new IllegalStateException("Pipe capacity/EOF");
                copyIn(source, offset, length);
            } finally { lock.unlock(); }
        }

        private void copyIn(byte[] source, int offset, int length) {
            int tail = (head + size) % bytes.length;
            int first = Math.min(length, bytes.length - tail);
            System.arraycopy(source, offset, bytes, tail, first);
            System.arraycopy(source, offset + first, bytes, 0, length - first);
            size += length;
            highWater.accumulateAndGet(size, Math::max);
            changed.signalAll();
        }

        int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            lock.lock();
            try {
                while (size == 0 && !finished && failure == null) await();
                checkFailure();
                if (size == 0) return -1;
                int count = Math.min(length, Math.min(size, bytes.length - head));
                System.arraycopy(bytes, head, target, offset, count);
                consumed(count);
                return count;
            } finally { lock.unlock(); wakeup.run(); }
        }

        void write(byte[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            while (length > 0) {
                lock.lock();
                try {
                    while (size == bytes.length && failure == null) await();
                    checkFailure();
                    int count = Math.min(length, bytes.length - size);
                    copyIn(source, offset, count);
                    offset += count;
                    length -= count;
                } finally { lock.unlock(); wakeup.run(); }
            }
        }

        int drain(WritableByteChannel channel, int quota) throws IOException {
            lock.lock();
            try {
                checkFailure();
                int count = channel.write(ByteBuffer.wrap(bytes, head, Math.min(quota, Math.min(size, bytes.length - head))));
                consumed(count);
                return count;
            } finally { lock.unlock(); }
        }

        private void consumed(int count) {
            head = (head + count) % bytes.length;
            size -= count;
            changed.signalAll();
        }

        void awaitDrained() throws IOException {
            lock.lock();
            try {
                while (size != 0 && failure == null) await();
                checkFailure();
            } finally { lock.unlock(); }
        }

        void finish() {
            lock.lock();
            try { finished = true; changed.signalAll(); } finally { lock.unlock(); }
        }

        void fail(IOException error) {
            lock.lock();
            try { failure = error; size = 0; changed.signalAll(); } finally { lock.unlock(); }
        }

        private void checkFailure() throws IOException { if (failure != null) throw failure; }

        private void await() throws InterruptedIOException {
            try { changed.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Pipe wait interrupted");
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        selector.wakeup();
        loop.join(2000);
        workers.shutdownNow();
        boolean workersStopped = workers.awaitTermination(2, TimeUnit.SECONDS);
        if (loop.isAlive() || !workersStopped) throw new IllegalStateException("Prototype did not stop within deadline");
        if (loopFailure != null) throw new AssertionError("Prototype loop failed", loopFailure);
    }
}
