package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@NullMarked
class Http2Connection extends BaseHttpConnection {
    private enum State {
        OPEN(true), HALF_CLOSED_LOCAL(true), HALF_CLOSED_REMOTE(false), CLOSED(false);
        final boolean canRead;
        State(boolean canRead) {
            this.canRead = canRead;
        }
    }
    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final Http2Settings serverSettings;
    private Http2Settings clientSettings = Http2Settings.DEFAULT_CLIENT_SETTINGS;
    private final ByteBuffer buffer;
    private volatile State state = State.OPEN;
    private volatile int lastStreamId = Integer.MAX_VALUE;
    @Nullable
    private OutputStream clientOut;
    private final Http2FlowController incomingFlowControl = new Http2FlowController(0, 65535);
    private final Http2FlowController outgoingFlowControl = new Http2FlowController(0, 65535);
    private final ConcurrentLinkedQueue<Long> settingsAckQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final Lock writeLock = new ReentrantLock();

    final FieldBlockEncoder fieldBlockEncoder;

    Http2Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime, Http2Settings initialServerSettings, ExecutorService executorService) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.serverSettings = initialServerSettings;
        this.executorService = executorService;
        this.buffer = ByteBuffer.allocate(serverSettings.maxFrameSize).flip();
        this.fieldBlockEncoder = new FieldBlockEncoder(new HpackTable(clientSettings.headerTableSize));
    }

    int maxFrameSize() {
        return clientSettings.maxFrameSize;
    }

    private synchronized void onRemoteClose() {
        if (state == State.OPEN) {
            state = State.HALF_CLOSED_REMOTE;
        } else if (state == State.HALF_CLOSED_LOCAL) {
            state = State.CLOSED;
        }
        log.info("State is now " + state);
    }

    private synchronized State state() {
        return state;
    }

    void write(LogicalHttp2Frame frame) throws IOException {
        writeLock.lock();
        try {
            log.info("Writing " + frame);
            frame.writeTo(this, clientOut);
        } finally {
            writeLock.unlock();
        }
    }

    void flush() throws IOException {
        writeLock.lock();
        try {
            clientOut.flush();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void start(InputStream clientIn, OutputStream clientOut) throws Http2Exception, IOException, ExecutionException, InterruptedException, TimeoutException {
        this.clientOut = clientOut;
        // do the handshake
        clientSettings = Http2Handshaker.handshake(this, serverSettings, clientSettings, buffer, clientIn,  clientOut);
        fieldBlockEncoder.changeTableSize(clientSettings.headerTableSize);
        settingsAckQueue.add(System.currentTimeMillis());

        var fieldBlockDecoder = new FieldBlockDecoder(new HpackTable(serverSettings.headerTableSize), server.maxUrlSize(), server.maxRequestHeadersSize());

        // and now just read frames
        while (state().canRead) {
            try {
                Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);
            } catch (SocketException e) {
                if (state() == State.CLOSED) {
                    log.info("Socket closed gracefully");
                    break;
                } else throw e;
            }
            var fh = Http2FrameHeader.readFrom(buffer);
            var len = fh.length();
            Mutils.readAtLeast(buffer, clientIn, len);
            log.info("read fh = " + fh);

            if (state == State.HALF_CLOSED_LOCAL && fh.streamId() > lastStreamId) {
                discardPayload(buffer, clientIn, len);
            } else {

                switch (fh.frameType()) {
                    case HEADERS: {
                        if (state == State.OPEN) {
                            lastStreamId = fh.streamId();
                        }
                        try {
                            var headerFragment = Http2HeadersFrame.readLogicalFrame(fh, fieldBlockDecoder, buffer, clientIn);
                            log.info("Got headers " + headerFragment);
                            startRequest(headerFragment);
                        } catch (Http2Exception e) {
                            if (e.errorType() == Http2Level.STREAM) {
                                throw new UnsupportedOperationException("Stream reset");
                            } else {
                                throw e;
                            }
                        } catch (HttpException e) {
                            // return an http response
                            FieldBlock errorHeaders = new FieldBlock();
                            errorHeaders.add(HeaderNames.PSEUDO_STATUS, e.status());
                            errorHeaders.add(e.responseHeaders());
                            byte[] message = e.getMessage().getBytes(StandardCharsets.UTF_8);
                            if (outgoingFlowControl.withdrawIfCan(message.length)) {
                                errorHeaders.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=utf-8");
                                errorHeaders.set(HeaderNames.CONTENT_LENGTH, message.length);
                                server.getStatsImpl().onInvalidRequest();
                                write(new Http2HeadersFrame(fh.streamId(), false, false, 0, 0, errorHeaders));
                                write(new Http2DataFrame(fh.streamId(), true, message, 0, message.length));
                            } else {
                                write(new Http2HeadersFrame(fh.streamId(), false, true, 0, 0, errorHeaders));
                            }
                        }
                        break;
                    }
                    case SETTINGS: {
                        var settingsDiff = Http2Settings.readFrom(fh, buffer);
                        if (settingsDiff.isAck) {
                            var ackedOne = settingsAckQueue.poll();
                            if (ackedOne == null) {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Settings ack without pending settings");
                            } else {
                                log.info("Settings acked after " + (System.currentTimeMillis() - ackedOne) + "ms");
                            }
                        } else {
                            var oldSettings = clientSettings;
                            var newSettings = settingsDiff.copyIfChanged(clientSettings);
                            if (newSettings != oldSettings) {
                                clientSettings = newSettings;
                                if (newSettings.initialWindowSize != oldSettings.initialWindowSize) {
                                    // todo: apply diff settings on existing streams
                                }

                            }
                        }
                        break;
                    }
                    case WINDOW_UPDATE: {
                        var windowUpdate = Http2WindowUpdate.readFrom(fh, buffer);
                        incomingFlowControl.applyWindowUpdate(windowUpdate);
                        if (windowUpdate.level() == Http2Level.STREAM) {
                            Http2Stream stream = streams.get(windowUpdate.streamId());
                            if (stream != null) {
                                stream.applyWindowUpdate(windowUpdate);
                            }
                        }
                        break;
                    }
                    case GOAWAY: {
                        var goaway = Http2GoAway.readFrom(fh, buffer);
                        onRemoteClose();
                        System.out.println(goaway);
                        break;
                    }
                    default: {
                        log.info("Discarding " + len + " bytes for unsupported type " + fh);
                        discardPayload(buffer, clientIn, len);
                    }
                }
            }

            // TODO: end if pending settings ack not received
        }

    }

    private void startRequest(Http2HeadersFrame frame) throws Http2Exception {
        var stream = Http2Stream.start(this, frame, serverSettings, clientSettings);
        streams.put(frame.streamId(), stream);
        onRequestStarted(stream.request);
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    handleExchange(stream.request, stream.response());
                    stream.cleanup();
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                } finally {
                    onExchangeEnded(stream);
                }

            }
        });
    }

    private void discardPayload(ByteBuffer buffer, InputStream clientIn, int len) throws IOException {
        while (len > 0) {
            // first ignore stuff already in the buffer
            if (buffer.hasRemaining()) {
                if (len >= buffer.remaining()) {
                    // reset the buffer completely
                    len -= buffer.remaining();
                    buffer.clear().flip();
                } else {
                    buffer.position(buffer.position() + len);
                    len = 0;
                }
            }
            if (len > 0) {
                while (len > buffer.capacity()) {
                    Mutils.readAtLeast(buffer, clientIn, buffer.capacity());
                    buffer.clear();
                    len -= buffer.capacity();
                }
                if (len > 0) {
                    Mutils.readAtLeast(buffer, clientIn, len);
                    buffer.flip();
                    len = 0;
                }
            }
        }
    }


    @Override
    public void abortWithTimeout() {
        // TODO do something with this
        abort();
    }

    @Override
    void initiateGracefulShutdown() throws IOException {
        // TODO thread safety
        if (state == State.OPEN) {
            state = State.HALF_CLOSED_LOCAL;
            var goaway = new Http2GoAway(lastStreamId, Http2ErrorCode.NO_ERROR.code(), null);
            write(goaway);
            flush();
            if (activeRequests().isEmpty()) {
                forceShutdown();
            }
        } else if (state == State.HALF_CLOSED_REMOTE) {
            // TODO!!
            log.warn("graceful shutdown with state " + state);
        } else {
            log.warn("graceful shutdown with state " + state);
        }
    }

    @Override
    boolean isShutdown() {
        return state == State.CLOSED;
    }

    @Override
    void forceShutdown() {
        // todo: thread safety
        if (state != State.CLOSED) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.info("Error force closing http2 socket", e);
            } finally {
                state = State.CLOSED;
            }
        }
    }

    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return streams.values().stream().map(s -> s.request).collect(Collectors.toSet());
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return Collections.emptySet();
    }

    @Override
    public void abort() {
        forceShutdown();
    }

    @Override
    protected void onExchangeEnded(ResponseInfo exchange) {
        var stream = (Http2Stream) exchange;
        streams.remove(stream.id);
        super.onExchangeEnded(exchange);
        if (state == State.HALF_CLOSED_LOCAL && streams.isEmpty()) {
            forceShutdown();
        }
    }
}
