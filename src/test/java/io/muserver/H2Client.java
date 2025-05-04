package io.muserver;

import javax.net.ssl.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import static scaffolding.ClientUtils.veryTrustingTrustManager;

class H2Client implements Closeable  {

    private static final SSLSocketFactory sslSocketFactory;

    static {
        var trustAllCertificates = new TrustManager[]{veryTrustingTrustManager()};
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCertificates, new java.security.SecureRandom());

            sslSocketFactory = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    public H2ClientConnection connect(MuServer server) throws IOException {
        return connect(server.uri().getPort());
    }
    public H2ClientConnection connect(int port) throws IOException {
        var socket = (SSLSocket) sslSocketFactory.createSocket("localhost", port);
        socket.setUseClientMode(true);

        var sslParameters = socket.getSSLParameters();
        sslParameters.setApplicationProtocols(new String[]{"h2"});
        socket.setSSLParameters(sslParameters);

        socket.startHandshake();
        if (!"h2".equals(socket.getApplicationProtocol())) {
            try (socket) {
                throw new IOException("Expected h2, got " + socket.getApplicationProtocol());
            }
        }

        return new H2ClientConnection(socket);
    }

    @Override
    public void close() {
    }
}

class H2ClientConnection implements Http2Peer, Closeable {
    private final SSLSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final ByteBuffer readBuffer;
    private int maxFrameSize = 16384;
    private final FieldBlockEncoder fieldBlockEncoder = new FieldBlockEncoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize));
    private final FieldBlockDecoder fieldBlockDecoder = new FieldBlockDecoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize), 32768, 32768);

    H2ClientConnection(SSLSocket socket) throws IOException {
        this.socket = socket;
        // TODO consider if each should be buffered
        inputStream = new BufferedInputStream(socket.getInputStream());
        outputStream = new BufferedOutputStream(socket.getOutputStream());
        readBuffer = ByteBuffer.allocate(16384).flip();
    }

    public void writePreface() throws IOException {
        outputStream.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }
    public void flush() throws IOException {
        outputStream.flush();
    }
    public H2ClientConnection writeFrame(LogicalHttp2Frame frame) throws IOException {
        frame.writeTo(this, outputStream);
        return this;
    }

    Http2FrameHeader readFrameHeader() throws IOException, Http2Exception {
        Mutils.readAtLeast(readBuffer, inputStream, Http2FrameHeader.FRAME_HEADER_LENGTH);
        return Http2FrameHeader.readFrom(readBuffer);
    }

    LogicalHttp2Frame readLogicalFrame(Http2FrameHeader header) throws IOException, Http2Exception {
        Mutils.readAtLeast(readBuffer, inputStream, header.length());
        switch (header.frameType()) {
            case GOAWAY: return Http2GoAway.readFrom(header, readBuffer);
            case SETTINGS: return Http2Settings.readFrom(header, readBuffer);
            case RST_STREAM: return Http2ResetStreamFrame.readFrom(header, readBuffer);
            case HEADERS: return Http2HeadersFrame.readLogicalFrame(header, fieldBlockDecoder, readBuffer, inputStream);
        }
        throw new RuntimeException("Unexpected frameType: " + header.frameType());
    }

    LogicalHttp2Frame readLogicalFrame() throws IOException, Http2Exception {
        LogicalHttp2Frame frame = readLogicalFrame(readFrameHeader());
        if (frame instanceof Http2Settings) {
            var settings = (Http2Settings) frame;
            if (!settings.isAck) {
                this.maxFrameSize = settings.maxFrameSize;
            }
        }
        return frame;
    }

    <T extends LogicalHttp2Frame> T readLogicalFrame(Class<T> clazz) throws Http2Exception, IOException {
        var frame = readLogicalFrame();
        if (clazz.isAssignableFrom(frame.getClass())) {
            return (T)frame;
        }
        throw new IllegalStateException("Expected " + clazz.getName() + ", got " + frame);
    }

    @Override
    public void close() throws IOException {
        flush();
        socket.close();
    }

    public H2ClientConnection writeRaw(byte[] bytes) throws IOException {
        outputStream.write(bytes);
        return this;
    }

    public void handshake() throws IOException, Http2Exception {
        writePreface();
        writeFrame(Http2Settings.DEFAULT_CLIENT_SETTINGS);
        flush();
        var settings1 = readLogicalFrame(Http2Settings.class);
        var settings2 = readLogicalFrame(Http2Settings.class);
        if (settings1.isAck ^ settings2.isAck) {
            writeFrame(Http2Settings.ACK);
        } else {
            throw new IllegalStateException("Expected single ACK, got " + settings1 + " and " + settings2);
        }

    }

    @Override
    public int maxFrameSize() {
        return maxFrameSize;
    }

    @Override
    public FieldBlockEncoder fieldBlockEncoder() {
        return fieldBlockEncoder;
    }
}