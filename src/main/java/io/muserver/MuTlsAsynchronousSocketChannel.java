package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.spi.AsynchronousChannelProvider;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MuTlsAsynchronousSocketChannel extends AsynchronousSocketChannel {
    private static final Logger log = LoggerFactory.getLogger(MuTlsAsynchronousSocketChannel.class);

    private final AsynchronousSocketChannel socketChannel;
    private final SSLEngine sslEngine;
    private SSLEngineResult.HandshakeStatus handshakeStatus;
    private final ByteBuffer appBuffer;
    private final ByteBuffer netBuffer;
    private MuHttp1Connection muConnection;
    private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    public MuTlsAsynchronousSocketChannel(AsynchronousSocketChannel socketChannel, SSLEngine sslEngine, ByteBuffer appBuffer, ByteBuffer netBuffer) {
        super(socketChannel.provider());
        this.socketChannel = socketChannel;
        this.sslEngine = sslEngine;
        this.handshakeStatus = sslEngine.getHandshakeStatus();
        this.appBuffer = appBuffer;
        this.netBuffer = netBuffer;
    }

    protected MuTlsAsynchronousSocketChannel(AsynchronousChannelProvider provider) {
        super(provider);
        throw new IllegalStateException();
    }

    public void beginHandshake(MuHttp1Connection connection) throws SSLException {
        this.muConnection = connection;
        pendingTasks.add(() -> {
            SSLSession session = sslEngine.getSession();
            muConnection.handshakeComplete(session.getProtocol(), session.getCipherSuite());
            ((MuServer2)muConnection.server()).onConnectionAccepted(muConnection);
        });
        sslEngine.beginHandshake();
        handshakeStatus = sslEngine.getHandshakeStatus();
        doHandshake();
    }

    private void doHandshake() {
        try {
            switch (handshakeStatus) {
                case NEED_UNWRAP -> {
                    netBuffer.clear();
                    socketChannel.read(netBuffer, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer result, Void attachment) {
                            netBuffer.flip();
                            try {
                                appBuffer.clear();
                                while (netBuffer.hasRemaining()) {
                                    var unwrapResult = sslEngine.unwrap(netBuffer, appBuffer);
                                    if (unwrapResult.getStatus() != SSLEngineResult.Status.OK && unwrapResult.getStatus() != SSLEngineResult.Status.CLOSED)
                                        throw new RuntimeException("Unwrapping not complete: " + unwrapResult);
                                    handshakeStatus = unwrapResult.getHandshakeStatus();
//                                    log.info("sslResult = " + unwrapResult + " ; netbuffer remaining=" + netBuffer.remaining());
                                }
                                doHandshake();
                            } catch (Throwable e) {
                                abortHandshake(e);
                            }
                        }

                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            abortHandshake(exc);
                        }

                    });
                }
                case NOT_HANDSHAKING, FINISHED -> {
                    log.info("Not handshaking! ");
                    Runnable toDo = pendingTasks.poll();
                    while (toDo != null) {
                        toDo.run();
                        toDo = pendingTasks.poll();
                    }
                }
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        log.info("Running " + task);
                        task.run(); // TODO run async
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    doHandshake();
                }
                case NEED_WRAP -> {
                    netBuffer.clear();
                    appBuffer.clear();
                    SSLEngineResult wrapResult = sslEngine.wrap(appBuffer, netBuffer);
//                    log.info("Wrap result: " + wrapResult);
                    // TODO: handle status=closed and buffer overflow for TLS handshake error
                    if (wrapResult.getStatus() != SSLEngineResult.Status.OK && wrapResult.getStatus() != SSLEngineResult.Status.CLOSED) {
                        throw new RuntimeException("Got " + wrapResult + " while wrapping");
                    }
                    netBuffer.flip();
                    handshakeStatus = wrapResult.getHandshakeStatus();
                    writeNetbuffer();
                }

                default -> log.info("Unexpected TLS handshake status: " + handshakeStatus);
            }
        } catch (Throwable e) {
            abortHandshake(e);
        }
    }

    private void abortHandshake(Throwable exc) {
        log.error("Error while handshaking", exc);
        closeQuietly();
        // todo: what to do with pending items?
        // e.g. if TLS-close handshaking is aborted then it should remove the connection from the server connections still
    }

    private void writeNetbuffer() {
        socketChannel.write(netBuffer, null, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, Object attachment) {
                if (result < 0) throw new RuntimeException("Got " + result + " while writing encrypted data");
                if (netBuffer.hasRemaining()) {
                    log.info("Still " + netBuffer.remaining() + " netBuffer to send");
                    writeNetbuffer();
                } else {
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    doHandshake();
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                log.error("Error while writing during handshake", exc);
                closeQuietly();
            }
        });
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
        sslEngine.closeInbound();
        return socketChannel.shutdownInput();
    }

    @Override
    public AsynchronousSocketChannel shutdownOutput() {
        throw new RuntimeException("Use the async version please");
    }

    public <A> void shutdownOutputAsync(CompletionHandler<Void, A> handler, A attachment) {
        log.info("Closing sslEngine outbound");
        sslEngine.closeOutbound();
        handshakeStatus = sslEngine.getHandshakeStatus();
        pendingTasks.add(() -> {
            log.info("Graceful TLS shutdown complete so closing output. Inbound done=" + sslEngine.isInboundDone());
            try {
                socketChannel.shutdownOutput();
                handler.completed(null, attachment);
            } catch (IOException e) {
                handler.failed(e, attachment);
            }
        });
        doHandshake();
    }

    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return socketChannel.getRemoteAddress();
    }

    @Override
    public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) {
        throw new IllegalStateException();
    }

    @Override
    public Future<Void> connect(SocketAddress remote) {
        throw new IllegalStateException();
    }

    @Override
    public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        netBuffer.clear();
        socketChannel.read(netBuffer, timeout, unit, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
                if (result == -1) {
                    log.info("TLS CLOSE_NOTIFY received");
                    try {
                        sslEngine.closeInbound();
                        handshakeStatus = sslEngine.getHandshakeStatus();
                        if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                            pendingTasks.add(() -> handler.completed(result, attachment));
                            doHandshake();
                        } else {
                            handler.completed(result, attachment);
                        }
                    } catch (Throwable e) {
                        handler.failed(e, attachment);
                    }
                } else {
                    netBuffer.flip();
                    SSLEngineResult unwrapResult;
                    try {
                        unwrapResult = sslEngine.unwrap(netBuffer, dst); // TODO handle buffer overflow
                    } catch (SSLException e) {
                        handler.failed(e, attachment);
                        return;
                    }
                    if (unwrapResult.getStatus() == SSLEngineResult.Status.CLOSED) {
                        handshakeStatus = unwrapResult.getHandshakeStatus();
                        pendingTasks.add(() -> handler.completed(-1, attachment));
                        doHandshake();
                    } else if (unwrapResult.getStatus() != SSLEngineResult.Status.OK) {
                        handler.failed(new SSLException("Result from decrypting: " + unwrapResult), attachment); // TODO: handle states where more reading needed
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
        throw new RuntimeException("Not implemented");
    }

    @Override
    public <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        netBuffer.clear();
        try {
            sslEngine.wrap(src, netBuffer);
        } catch (SSLException e) {
            handler.failed(e, attachment);
            return;
        }
        netBuffer.flip();
        socketChannel.write(netBuffer, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
                handler.completed(result, attachment);
            }

            @Override
            public void failed(Throwable exc, A attachment) {
                handler.failed(exc, attachment);
            }
        });
    }

    @Override
    public Future<Integer> write(ByteBuffer src) {
        var fut = new CompletableFuture<Integer>();
        write(src, null, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, Object attachment) {
                fut.complete(result);
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                fut.completeExceptionally(exc);
            }
        });
        return fut;
    }

    @Override
    public <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        throw new RuntimeException("Not implemented");
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
