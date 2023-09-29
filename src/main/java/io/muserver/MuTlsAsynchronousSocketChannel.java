package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.spi.AsynchronousChannelProvider;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MuTlsAsynchronousSocketChannel extends AsynchronousSocketChannel {
    private static final Logger log = LoggerFactory.getLogger(MuTlsAsynchronousSocketChannel.class);

    private final AsynchronousSocketChannel socketChannel;
    private final SSLEngine sslEngine;
    private SSLEngineResult.HandshakeStatus handshakeStatus;
    private final ByteBuffer appReadBuffer;
    private final ByteBuffer netReadBuffer;
    private final ByteBuffer appWriteBuffer;
    private final ByteBuffer netWriteBuffer;
    private MuHttp1Connection muConnection;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final long handshakeActionTimeoutMillis = 5000;

    public MuTlsAsynchronousSocketChannel(AsynchronousSocketChannel socketChannel, SSLEngine sslEngine, ByteBuffer appReadBuffer, ByteBuffer netReadBuffer, ByteBuffer appWriteBuffer, ByteBuffer netWriteBuffer) {
        super(socketChannel.provider());
        this.socketChannel = socketChannel;
        this.sslEngine = sslEngine;
        this.handshakeStatus = sslEngine.getHandshakeStatus();
        this.appReadBuffer = appReadBuffer;
        this.netReadBuffer = netReadBuffer;
        this.appWriteBuffer = appWriteBuffer;
        this.netWriteBuffer = netWriteBuffer;
    }

    protected MuTlsAsynchronousSocketChannel(AsynchronousChannelProvider provider) {
        super(provider);
        throw new IllegalStateException();
    }

    public void beginHandshake(MuHttp1Connection connection, DoneCallback callback) throws SSLException {
        this.muConnection = connection;
        sslEngine.beginHandshake();
        handshakeStatus = sslEngine.getHandshakeStatus();
        doHandshake(callback);
    }

    private void doHandshake(DoneCallback callback) {
        log.info("In handshake loop: " + handshakeStatus + " - " + sslEngine.getHandshakeStatus());
        try {
            switch (handshakeStatus) {
                case NEED_UNWRAP -> {
                    appReadBuffer.clear();
                    read(appReadBuffer, handshakeActionTimeoutMillis, TimeUnit.MILLISECONDS, null, new CompletionHandler<>() {
                        @Override
                        public void completed(Integer result, Object attachment) {
                            doHandshake(callback);
                        }
                        @Override
                        public void failed(Throwable exc, Object attachment) {
                            callback.onComplete(exc);
                        }
                    });
                }
                case NOT_HANDSHAKING, FINISHED -> {
                    callback.onComplete(null);
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        log.info("Running " + task);
                        task.run(); // TODO run async
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    doHandshake(callback);
                }
                case NEED_WRAP -> {
                    appWriteBuffer.clear();
                    write(new ByteBuffer[]{appWriteBuffer}, 0, 1, handshakeActionTimeoutMillis, TimeUnit.MILLISECONDS, null, new CompletionHandler<>() {
                        @Override
                        public void completed(Long result, Object attachment) {
                            doHandshake(callback);
                        }

                        @Override
                        public void failed(Throwable exc, Object attachment) {
                            callback.onComplete(exc);
                        }
                    });
                }

                default -> log.info("Unexpected TLS handshake status: " + handshakeStatus);
            }
        } catch (Throwable e) {
            callback.onComplete(e);
        }
    }


    @Override
    public AsynchronousSocketChannel bind(SocketAddress local) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public <T> AsynchronousSocketChannel setOption(SocketOption<T> name, T value) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return socketChannel.getOption(name);
    }

    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return socketChannel.supportedOptions();
    }

    @Override
    public AsynchronousSocketChannel shutdownInput() throws IOException {
        log.info("Closing sslEngine inbound");
        sslEngine.closeInbound(); // todo handshake? or not because this type is initiated from the client?
        return socketChannel.shutdownInput();
    }

    @Override
    public AsynchronousSocketChannel shutdownOutput() {
        throw new RuntimeException("Use the async version please");
    }

    public <A> void shutdownOutputAsync(CompletionHandler<Void, A> handler, A attachment) {
        boolean shuttingDown = shutdownInitiated.compareAndSet(false, true);
        if (!shuttingDown) {
            log.info("Already was shut down so doing nothing");
            handler.failed(new RuntimeException("already closing"), attachment);
            return;
        }
        log.info("Closing sslEngine outbound");
        sslEngine.closeOutbound();
        handshakeStatus = sslEngine.getHandshakeStatus();
        log.info("hs=" + handshakeStatus);
        doHandshake(error -> {
            if (error == null) {
                log.info("Graceful TLS shutdown complete so closing output. Inbound done=" + sslEngine.isInboundDone());
                try {
                    socketChannel.shutdownOutput();
                    handler.completed(null, attachment);
                } catch (IOException e) {
                    handler.failed(e, attachment);
                }
            } else {
                log.info("Error doing graceful shutdown", error);
                handler.failed(error, attachment);
            }
        });

    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return socketChannel.getRemoteAddress();
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Void> connect(SocketAddress remote) {
        throw new UnsupportedOperationException();
    }


    private final AtomicLong readCount = new AtomicLong();
    @Override
    public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        netReadBuffer.clear();
        var i = readCount.incrementAndGet();
        log.info("Read " + i);
        socketChannel.read(netReadBuffer, timeout, unit, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
                log.info("Read " + i + " complete");
                if (result == -1) {
                    log.info("TLS CLOSE_NOTIFY received as read -1 bytes: " + sslEngine.isInboundDone() + " and handshake status is " + sslEngine.getHandshakeStatus());
                    try {
                        sslEngine.closeInbound();
                        handshakeStatus = sslEngine.getHandshakeStatus();
                        if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                            doHandshake(error -> {
                                if (error == null) {
                                    handler.completed(result, attachment);
                                } else {
                                    handler.failed(error, attachment);
                                }
                            });
                        } else {
                            handler.completed(result, attachment);
                        }
                    } catch (Throwable e) {
                        handler.failed(e, attachment);
                    }
                } else {
                    netReadBuffer.flip();
                    SSLEngineResult unwrapResult;
                    try {

                        do {
                            unwrapResult = sslEngine.unwrap(netReadBuffer, dst);
                            handshakeStatus = unwrapResult.getHandshakeStatus();
                        } while (netReadBuffer.hasRemaining() && dst.hasRemaining() && unwrapResult.getStatus() == SSLEngineResult.Status.OK);

//                        log.info("unwrap result: " + unwrapResult);

                        // TODO handle buffer overflow if nothing was read and buffer underflow
                    } catch (SSLException e) {
                        log.warn("SSLException: " + sslEngine.getHandshakeStatus());
                        handler.failed(e, attachment);
                        return;
                    }
                    if (unwrapResult.getStatus() == SSLEngineResult.Status.CLOSED) {
                        doHandshake(error -> handler.failed(new ClosedChannelException(), attachment));
                    } else {
                        handler.completed(result, attachment);
                    }
                }
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                handler.failed(exc, attachment);
            }
        });
    }

    @Override
    public Future<Integer> read(ByteBuffer dst) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<Integer> write(ByteBuffer src) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        netWriteBuffer.clear();
        try {
            SSLEngineResult result = sslEngine.wrap(srcs, offset, length, netWriteBuffer);
            handshakeStatus = result.getHandshakeStatus();
            if (result.getStatus() != SSLEngineResult.Status.OK && result.getStatus() != SSLEngineResult.Status.CLOSED) {
                // todo handle this properly
                throw new SSLException("Got a " + result + " when encrypting data");
            }
        } catch (SSLException e) {
            handler.failed(e, attachment);
            return;
        }
        netWriteBuffer.flip();
        socketChannel.write(new ByteBuffer[] { netWriteBuffer }, 0, 1, timeout, unit, attachment, handler);
    }

    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return socketChannel.getLocalAddress();
    }

    @Override
    public boolean isOpen() {
        return socketChannel.isOpen();
    }

    @Override
    public void close() throws IOException {
        log.info("Closing tls channel when " + handshakeStatus);
        socketChannel.close();
    }

    public void closeQuietly() {
        try {
            if (socketChannel.isOpen()) {
                close();
            }
        } catch (IOException e) {
            log.info("Ignoring exception on socket close: " + e.getMessage());
        }
    }

}
