package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class AsyncTlsSocketChannel implements MuSocketChannel {
    private static final Logger log = LoggerFactory.getLogger(AsyncTlsSocketChannel.class);

    private final AsynchronousSocketChannel socketChannel;
    private final SSLEngine engine;
    private final ByteBuffer appReadBuffer;
    private final ByteBuffer netReadBuffer;
    private final static ByteBuffer appWriteBuffer = Mutils.EMPTY_BUFFER;
    private final ByteBuffer netWriteBuffer;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final long handshakeIOTimeout;
    private final long readTimeout;
    private final long writeTimeout;
    private volatile SSLEngineResult engineResult;

    public AsyncTlsSocketChannel(AsynchronousSocketChannel socketChannel, SSLEngine engine, ByteBuffer appReadBuffer, ByteBuffer netReadBuffer, ByteBuffer netWriteBuffer, long handshakeIOTimeout, long readTimeout, long writeTimeout) {
        this.socketChannel = socketChannel;
        this.engine = engine;
        this.appReadBuffer = appReadBuffer;
        this.netReadBuffer = netReadBuffer;
        this.netWriteBuffer = netWriteBuffer;
        this.handshakeIOTimeout = handshakeIOTimeout;
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
    }

    @Override
    public ByteBuffer readBuffer() {
        return appReadBuffer;
    }

    @Override
    public boolean isOpen() {
        return socketChannel.isOpen();
    }

    public void beginHandshake(DoneCallback callback) throws SSLException {
        engine.beginHandshake();
        doHandshake(callback);
    }

    private void doHandshake(DoneCallback callback) {
        try {
            if (canFlush()) {
                flush(err -> {
                    if (err == null) {
                        doHandshake(callback);
                    } else {
                        callback.onComplete(err);
                    }
                });
                return;
            }

            switch (engine.getHandshakeStatus()) {
                case NOT_HANDSHAKING, FINISHED -> callback.onComplete(null);
                case NEED_TASK -> {
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        var start = System.currentTimeMillis();
                        task.run(); // TODO run async
                        log.info("Ran SSL task in " + (System.currentTimeMillis() - start) + "ms");
                    }
                    doHandshake(callback);
                }
                case NEED_WRAP -> {
                    appWriteBuffer.flip();
                    engineResult = engine.wrap(appWriteBuffer, netWriteBuffer);
                    appWriteBuffer.compact();

                    if (engineResult.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        // there is not enough space in the write buffer, so try flushing it and then send again
                        netWriteBuffer.flip();
                        socketChannel.write(netWriteBuffer, handshakeIOTimeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<>() {
                            @Override
                            public void completed(Integer result, Object attachment) {
                                netWriteBuffer.compact();
                                if (result > 0) {
                                    doHandshake(callback);
                                } else {
                                    // todo: is this the right thing?
                                    callback.onComplete(new SSLHandshakeException("Could not write data from net buffer after getting a buffer overflow"));
                                }
                            }

                            @Override
                            public void failed(Throwable exc, Object attachment) {
                                callback.onComplete(exc);
                            }
                        });
                    } else {
                        doHandshake(callback);
                    }
                }
                case NEED_UNWRAP -> {
                    netReadBuffer.flip();
                    engineResult = engine.unwrap(netReadBuffer, appReadBuffer);
                    netReadBuffer.compact();
                    if (engineResult.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // not enough data read from network so get more if it's still open
                        if (engine.isInboundDone()) {
                            // todo: how to handle this? suppose it is an error
                            callback.onComplete(new SSLHandshakeException("Inbound SSL connection was closed during NEED_UNWRAP"));
                        } else {
                            socketChannel.read(netReadBuffer, handshakeIOTimeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<>() {
                                @Override
                                public void completed(Integer result, Object attachment) {
                                    if (result > 0) {
                                        // there is more data available to unwrap, so we will try again
                                        doHandshake(callback);
                                    } else {
                                        // todo: is this the right thing?
                                        callback.onComplete(new SSLHandshakeException("Could not read data from net buffer after getting a buffer underflow"));
                                    }
                                }

                                @Override
                                public void failed(Throwable exc, Object attachment) {
                                    callback.onComplete(exc);
                                }
                            });

                        }

                    } else {
                        doHandshake(callback);
                    }
                }
                default -> log.info("Unexpected TLS handshake status: " + engine.getHandshakeStatus());
            }
        } catch (Throwable e) {
            callback.onComplete(e);
        }
    }


    @Override
    public void read(CompletionHandler<Integer, Void> handler) {
        int	plainTextCount = appReadBuffer.position();

        if (canFlush()) {
            flush(err -> {
                if (err == null) {
                    read(handler);
                } else {
                    handler.failed(err, null);
                }
            });
            return;
        }

        if (plainTextCount > 0) {
            log.warn("********** read buffer still has stuff!! " + plainTextCount);
            handler.completed(plainTextCount, null);
        } else if (engine.isInboundDone()) {
            handler.completed(-1, null);
        } else {
            if (netReadBuffer.position() > 0) {
                netReadBuffer.flip();
                int appReadBeforePos = appReadBuffer.position();
                try {
                    engineResult = engine.unwrap(netReadBuffer, appReadBuffer);
                } catch (SSLException e) {
                    handler.failed(e, null);
                }
                netReadBuffer.compact();
                if (engineResult.getStatus() == SSLEngineResult.Status.OK) {
                    handler.completed(appReadBuffer.position() - appReadBeforePos, null);
                    return;
                }
            }


            socketChannel.read(netReadBuffer, readTimeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    try {
                        if (result == -1) {
                            log.info("EOF received; closing inbound");
                            engine.closeInbound();
                        } else {
                            netReadBuffer.flip();
                            engineResult = engine.unwrap(netReadBuffer, appReadBuffer);
                            netReadBuffer.compact();
                            switch (engineResult.getStatus()) {
                                case BUFFER_UNDERFLOW -> {
                                    // not enough was read so get some more
                                    log.info("Buffer underflow while reading bytesRead=" + result + " engineResult=" + engineResult + " and " + netWriteBuffer + " and appReadBuffer=" + appReadBuffer);
                                    socketChannel.read(netReadBuffer, readTimeout, TimeUnit.MILLISECONDS, null, this);
                                    return;
                                }
                                case BUFFER_OVERFLOW -> {
                                    log.info("Not enough space unwrapping from " + netReadBuffer + " to " + appReadBuffer);
                                    handler.completed(0, null);
                                    return;
                                }
                                case CLOSED -> socketChannel.shutdownInput();
                            }
                        }
                        doHandshake(err -> {
                            if (err == null) {
                                int position = appReadBuffer.position();
                                int read = position == 0 && engine.isInboundDone() ? -1 : position;
                                handler.completed(read, null);
                            } else {
                                handler.failed(err, null);
                            }
                        });
                    } catch (Exception e) {
                        handler.failed(e, attachment);
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    handler.failed(exc, attachment);
                }
            });
        }

    }


    @Override
    public void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler) {

        try {
            engineResult = engine.wrap(srcs, offset, length, netWriteBuffer);
            switch (engineResult.getStatus()) {
                case BUFFER_UNDERFLOW -> handler.completed((long) engineResult.bytesConsumed(), null);
                case BUFFER_OVERFLOW -> {
                    long before = netWriteBuffer.position();
                    flush(err -> {
                        if (err == null) {
                            long wrote = netWriteBuffer.position() - before;
                            if (wrote == 0) {
                                handler.completed(wrote, null);
                            } else {
                                scatteringWrite(srcs, offset, length, handler);
                            }
                        } else {
                            handler.failed(err, null);
                        }
                    });
                }
                case CLOSED -> handler.failed(new IllegalStateException("Cannot write with SSLEngine status " + engine.getHandshakeStatus()), null);
                case OK -> {
                    int toWrite = netWriteBuffer.position();
                    doHandshake(error -> {
                        if (error == null) {
                            handler.completed((long) (toWrite - netWriteBuffer.position()), null);
                        } else {
                            handler.failed(error, null);
                        }
                    });
                }
            }

        } catch (Exception e) {
            handler.failed(e, null);
        }

    }


    public boolean canFlush() {
        return netWriteBuffer.position() > 0;
    }
    public void flush(DoneCallback callback) {
        netWriteBuffer.flip();
        var er = engineResult;
        var timeout = er == null || er.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ? handshakeIOTimeout : writeTimeout;
        socketChannel.write(netWriteBuffer, timeout, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
                netWriteBuffer.compact();
                if (canFlush()) {
                    log.info("Short write in flush - writing " + netWriteBuffer.remaining());
                    flush(callback);
                } else {
                    callback.onComplete(null);
                }
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                callback.onComplete(exc);
            }
        });
    }

    @Override
    public void close(DoneCallback callback) {
        boolean shuttingDown = shutdownInitiated.compareAndSet(false, true);
        if (!shuttingDown) {
            log.info("Already was shut down so doing nothing");
            callback.onComplete(new RuntimeException("already closing"));
            return;
        }
        if (canFlush()) {
            log.info("Flushing before initiating close");
            flush(err -> {
                if (err == null) {
                    close(callback);
                } else {
                    callback.onComplete(err);
                }
            });
            return;
        }
        log.info("Closing sslEngine outbound");
        engine.closeOutbound();
        log.info("hs=" + engine.getHandshakeStatus());
        doHandshake(error -> {
            if (error == null) {

                if (canFlush()) {
                    flush(flushErr -> {
                        if (flushErr == null) {
                            log.info("Graceful TLS shutdown complete so closing channel. Inbound done=" + engine.isInboundDone());
                            try {
                                // no need to wait for the client to respond, as per 7.2.1 of RFC 5246 for TLSv1.2 and 6.1 of RFC 8446 for TLSv1.3
                                socketChannel.close();
                                callback.onComplete(null);
                            } catch (IOException e) {
                                callback.onComplete(e);
                            }
                        } else {
                            callback.onComplete(flushErr);
                        }

                    });
                } else {
                    callback.onComplete(null);
                }

            } else {
                log.info("Error doing graceful shutdown", error);
                callback.onComplete(error);
            }
        });

    }

    @Override
    public void abort() throws IOException {
        log.info("Killing channel");
        socketChannel.close();
    }

}
