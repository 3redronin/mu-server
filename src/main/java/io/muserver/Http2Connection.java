package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

class Http2Connection extends BaseHttpConnection {
    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final Http2Settings serverSettings;
    private Http2Settings clientSettings = Http2Settings.DEFAULT_CLIENT_SETTINGS;
    private final ByteBuffer buffer;
    private volatile boolean closed = false;
    private final Http2FlowController connectionFlowControl = new Http2FlowController(0, 65535);
    private final ConcurrentLinkedQueue<Long> settingsAckQueue = new ConcurrentLinkedQueue<>();

    Http2Connection(@NotNull Mu3ServerImpl server, @NotNull ConnectionAcceptor creator, @NotNull Socket clientSocket, @Nullable Certificate clientCertificate, @NotNull Instant handshakeStartTime) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.serverSettings = creator.getHttp2Config().initialSettings();
        this.buffer = ByteBuffer.allocate(serverSettings.maxFrameSize).flip();
    }

    static void readAtLeast(ByteBuffer buffer, InputStream inputStream, int minBytes) throws IOException {
        if (minBytes > buffer.capacity()) throw new IllegalArgumentException("This buffer is not big enough");
        while (buffer.remaining() < minBytes) {
            if (buffer.capacity() - buffer.limit() < minBytes) {
                buffer.compact().flip();
            }
            int read = inputStream.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.capacity() - buffer.limit());
            if (read == -1) {
                throw new ClientDisconnectedException();
            }
            buffer.limit(buffer.limit() + read);
        }
    }




    @Override
    public void start(InputStream clientIn, OutputStream clientOut) throws Http2Exception, IOException {
        // do the handshake
        clientSettings = Http2Handshaker.handshake(serverSettings, clientSettings, buffer, clientIn,  clientOut);
        settingsAckQueue.add(System.currentTimeMillis());

        // and now just read frames
        while (!closed) {
            Http2Connection.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);
            var fh = Http2FrameHeader.readFrom(buffer);
            var len = fh.length();
            Http2Connection.readAtLeast(buffer, clientIn, len);

            System.out.println("fh = " + fh);

            switch (fh.frameType()) {

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
                    connectionFlowControl.applyWindowUpdate(windowUpdate);
                    if (windowUpdate.level() == Http2Level.STREAM) {
                        // todo: update relevant stream
                    }
                    break;
                }
                case GOAWAY: {
                    var goaway = Http2GoAway.readFrom(fh, buffer);
                    closed = true;
                    System.out.println(goaway);
                    break;
                }
                default: discardPayload(buffer, clientIn, len);
            }

            // TODO: end if pending settings ack not received
        }

        // TODO: wait for output queue to drain
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
                    readAtLeast(buffer, clientIn, buffer.capacity());
                    buffer.clear();
                    len -= buffer.capacity();
                }
                if (len > 0) {
                    readAtLeast(buffer, clientIn, len);
                    buffer.flip();
                    len = 0;
                }
            }
        }
    }


    @Override
    public void abortWithTimeout() {
        abort();
    }

    @NotNull
    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_2;
    }

    @NotNull
    @Override
    public Set<MuRequest> activeRequests() {
        return Set.of();
    }

    @NotNull
    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return Collections.emptySet();
    }

    @Override
    public void abort() {
        closed = true;
    }

}
