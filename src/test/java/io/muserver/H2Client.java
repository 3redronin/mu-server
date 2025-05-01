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

class H2ClientConnection implements Closeable {
    private final SSLSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final ByteBuffer readBuffer;

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
    public void flushOutput() throws IOException {
        outputStream.flush();
    }
    public void writeFrame(LogicalHttp2Frame frame) throws IOException {
        frame.writeTo(null, outputStream);
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
        }
        throw new RuntimeException("Unexpected frameType: " + header.frameType());
    }

    LogicalHttp2Frame readLogicalFrame() throws IOException, Http2Exception {
        return readLogicalFrame(readFrameHeader());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public void writeRaw(byte[] bytes) throws IOException {
        outputStream.write(bytes);
    }
}