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
    private CompletionHandler<Integer, ?> pendingHandler;

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
        sslEngine.beginHandshake();
        handshakeStatus = sslEngine.getHandshakeStatus();
        doHandshake();
    }

    private void closeNow() {
        sslEngine.getSession().invalidate(); // TODO do this?
        if (socketChannel.isOpen()) {
            try {
                socketChannel.close();
            } catch (IOException e) {
                log.info("Error closing channel: " + e.getMessage());
            }
        }
    }

    private void doHandshake() throws SSLException {
        log.info("handshakeStatus=" + handshakeStatus);
        switch (handshakeStatus) {
            case NEED_UNWRAP -> {
                netBuffer.clear();
                socketChannel.read(netBuffer, null, new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        netBuffer.flip();
                        log.info("Read " + result + " bytes and remaining " + netBuffer.remaining());
                        try {
                            appBuffer.clear();
                            var sslResult = sslEngine.unwrap(netBuffer, appBuffer);
                            if (sslResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW || sslResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW)
                                throw new RuntimeException("Unwrapping not complete: " + sslResult);
                            handshakeStatus = sslResult.getHandshakeStatus();
                            log.info("sslResult = " + sslResult);
                            doHandshake();
                        } catch (SSLException e) {
                            throw new RuntimeException("Error unwrapping data", e);
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        log.error("Error while unwrapping", exc);
                        closeNow();
                    }
                });
            }

            case NOT_HANDSHAKING -> {
                log.info("Not handshaking! ");
                if (pendingHandler != null) {
                    pendingHandler.completed(-1, null);
                }
            }
            case FINISHED -> {
                log.info("Finished handshake");
                SSLSession session = sslEngine.getSession();
                muConnection.handshakeComplete(session.getProtocol(), session.getCipherSuite());
                ((MuServer2)muConnection.server()).onConnectionAccepted(muConnection);
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
                var wrapResult = sslEngine.wrap(appBuffer, netBuffer);
                if (wrapResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW || wrapResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW)
                    throw new RuntimeException("Unwrapping not complete: " + wrapResult);
                netBuffer.flip();
                handshakeStatus = wrapResult.getHandshakeStatus();
                writeNetbuffer();
            }
            case NEED_UNWRAP_AGAIN -> {
                log.info("Need unwrap again");
            }
        }
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
                    try {
                        doHandshake();
                    } catch (SSLException e) {
                        handleSSLException(e);
                    }
                }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                log.error("Error while writing during handshake", exc);
                closeNow();
            }
        });
    }

    private void handleSSLException(SSLException e) {
        log.error("SSLException", e);
        closeNow();
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
    public AsynchronousSocketChannel shutdownOutput() throws IOException {
        log.info("Closing sslEngine outbound");
        sslEngine.closeOutbound();
        return socketChannel.shutdownOutput();
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
        log.info("TLS channel read with position=" + dst.position() + " and limit=" + dst.limit());
        netBuffer.clear();
        socketChannel.read(netBuffer, timeout, unit, attachment, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, A attachment) {
                netBuffer.flip();
                SSLEngineResult unwrapResult;
                try {
                    unwrapResult = sslEngine.unwrap(netBuffer, dst);
                } catch (SSLException e) {
                    handler.failed(e, attachment);
                    return;
                }
                if (unwrapResult.getStatus() == SSLEngineResult.Status.CLOSED) {
                    try {
                        handshakeStatus = unwrapResult.getHandshakeStatus();
                        pendingHandler = handler;
                        doHandshake();
                    } catch (SSLException e) {
                        handler.failed(e, attachment);
                    }
                } else if (unwrapResult.getStatus() != SSLEngineResult.Status.OK) {
                    handler.failed(new SSLException("Result from decrypting: " + unwrapResult), attachment); // TODO: handle states where more reading needed
                } else {
                    handler.completed(result, attachment);
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
        socketChannel.write(netBuffer, attachment, new CompletionHandler<Integer, A>() {
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
        sslEngine.closeOutbound();
    }

}
