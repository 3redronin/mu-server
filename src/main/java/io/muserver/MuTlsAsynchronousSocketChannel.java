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
                            if (sslResult.getStatus() != SSLEngineResult.Status.OK)
                                throw new RuntimeException("Unwrapping not complete: " + sslResult);
                            handshakeStatus = sslEngine.getHandshakeStatus();
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
                muConnection.readyToRead();
            }
            case FINISHED -> {
                log.info("Finished");
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
                if (wrapResult.getStatus() != SSLEngineResult.Status.OK)
                    throw new RuntimeException("Unwrapping not complete: " + wrapResult);
                netBuffer.flip();
                socketChannel.write(netBuffer, null, new CompletionHandler<Integer, Object>() {
                    @Override
                    public void completed(Integer result, Object attachment) {
                        if (result < 0) throw new RuntimeException("Got " + result + " while writing encrypted data");
                        if (netBuffer.hasRemaining()) throw new RuntimeException("Did not write all"); // Todo: write again
                        handshakeStatus = sslEngine.getHandshakeStatus();
                        try {
                            doHandshake();
                        } catch (SSLException e) {
                            throw new RuntimeException("Error continuing handshake after wrapping", e);
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Object attachment) {
                        log.error("Error while writing during handshake", exc);
                        closeNow();
                    }
                });

            }
            case NEED_UNWRAP_AGAIN -> {
                log.info("Need unwrap again");
            }
        }
    }

//    private void doHandshake() throws SSLException {
//        SSLEngineResult.Status status;
//        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
//        log.info("handshakeStatus = " + handshakeStatus);
//
//        if (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
//
//            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
//                // TODO: put this on another thread
//                Runnable task;
//                while ((task = sslEngine.getDelegatedTask()) != null) {
//                    log.info("Executing task " + task);
//                    task.run();
//                }
//            }
//
//            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
//                netBuffer.clear();
//                SSLEngineResult result = sslEngine.wrap(appBuffer, netBuffer);
//                status = result.getStatus();
//                handshakeStatus = result.getHandshakeStatus();
//                netBuffer.flip();
//                if (status == SSLEngineResult.Status.OK) {
//                    writeNetBuffer();
//                }
//            }
//
//            if (handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
//                if (netBuffer.hasRemaining()) {
//                    doUnwrap();
//                } else {
//                    // Read data into netBuffer
//                    socketChannel.read(netBuffer, null, new CompletionHandler<Integer, Void>() {
//                        @Override
//                        public void completed(Integer bytesRead, Void attachment) {
//                            if (bytesRead > 0) {
//                                netBuffer.flip();
//                                try {
//                                    doUnwrap();
//                                } catch (SSLException e) {
//                                    throw new RuntimeException("Error reading into netbuffer", e);
//                                }
//                            }
//                        }
//
//                        @Override
//                        public void failed(Throwable exc, Void attachment) {
//                            log.error("Failed reading netBuffer", exc);
//                            // TODO close stuff
//                        }
//                    });
//                }
//            }
//        }
//    }
//
//    private void writeNetBuffer() {
//        socketChannel.write(netBuffer, null, new CompletionHandler<Integer, Void>() {
//            @Override
//            public void completed(Integer bytesWritten, Void attachment) {
//                if (netBuffer.hasRemaining()) {
//                    writeNetBuffer();
//                } else {
//                    if (sslEngine.isOutboundDone()) {
//                        try {
//                            close();
//                        } catch (IOException e) {
//                            throw new UncheckedIOException("Error closing", e);
//                        }
//                    } else {
//                        try {
//                            doUnwrap();
//                        } catch (SSLException e) {
//                            throw new RuntimeException("Error unwrapping netbuffer", e);
//                        }
//                    }
//                }
//            }
//
//            @Override
//            public void failed(Throwable exc, Void attachment) {
//                log.error("Failed writing netBuffer", exc);
//            }
//        });
//    }
//
//    private void doUnwrap() throws SSLException {
//        SSLEngineResult result;
//        do {
//            appBuffer.clear();
//            result = sslEngine.unwrap(netBuffer, appBuffer);
//            netBuffer.compact();
//        } while (result.getStatus() == SSLEngineResult.Status.OK &&
//            result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_UNWRAP);
//
//        if (result.getStatus() == SSLEngineResult.Status.OK) {
//            appBuffer.flip();
//            // Process the decrypted data in appBuffer
//            // ...
//
//            // Prepare for the next read
//            netBuffer.flip();
//            socketChannel.read(netBuffer, null, new CompletionHandler<Integer, Void>() {
//                @Override
//                public void completed(Integer bytesRead, Void attachment) {
//                    if (bytesRead > 0) {
//                        netBuffer.flip();
//                        try {
//                            doUnwrap();
//                        } catch (SSLException e) {
//                            throw new RuntimeException("Error reading for status OK", e);
//                        }
//                    }
//                }
//
//                @Override
//                public void failed(Throwable exc, Void attachment) {
//                    log.error("Error reading in OK", exc);
//                }
//            });
//        } else if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
//            socketChannel.read(netBuffer, null, new CompletionHandler<Integer, Void>() {
//                @Override
//                public void completed(Integer bytesRead, Void attachment) {
//                    if (bytesRead > 0) {
//                        netBuffer.flip();
//                        try {
//                            doUnwrap();
//                        } catch (SSLException e) {
//                            throw new RuntimeException("Error reading while BUFFER_UNDERFLOW", e);
//                        }
//                    }
//                }
//
//                @Override
//                public void failed(Throwable exc, Void attachment) {
//                    log.info("Error reading in underflow", exc);
//                }
//            });
//        } else if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
//            try {
//                close();
//            } catch (IOException e) {
//                throw new UncheckedIOException("Error closing", e);
//            }
//        }
//    }

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
        return socketChannel.shutdownInput();
    }

    @Override
    public AsynchronousSocketChannel shutdownOutput() throws IOException {
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
        log.info("TLS channel read: " + dst.remaining());
        netBuffer.clear();
        socketChannel.read(netBuffer, timeout, unit, attachment, new CompletionHandler<Integer, A>() {
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
                if (unwrapResult.getStatus() != SSLEngineResult.Status.OK) {
                    handler.failed(new SSLException("Result from decrypting: " + unwrapResult), attachment); // TODO: handle states where more reading needed
                }
                handler.completed(result, attachment);

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
