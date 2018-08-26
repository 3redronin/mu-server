package io.muserver;

import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuResponseImpl implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(MuResponseImpl.class);

    private enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE
    }

    private final WritableByteChannel channel;
    private final MuRequestImpl request;
    private final boolean isKeepAlive;
    private int status = 200;
    private final MuHeaders headers = new MuHeaders();
    private OutputState state = OutputState.NOTHING;
    private final ResponseGenerator rg;
    private PrintWriter writer;
    private OutputStreamToByteChannelAdapter outputStream;
    private long bytesStreamed = 0;

    MuResponseImpl(WritableByteChannel channel, MuRequestImpl request, boolean isKeepAlive) {
        this.channel = channel;
        this.request = request;
        this.isKeepAlive = isKeepAlive;
        rg = new ResponseGenerator(HttpVersion.HTTP_1_1);
        headers.set(HeaderNames.DATE.toString(), Mutils.toHttpDate(new Date()));
    }

    Charset charset() {
        String contentType = headers.get(HeaderNames.CONTENT_TYPE);
        if (contentType == null) {
            return UTF_8;
        }
        // TODO: parse the charset
        return UTF_8;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public void status(int value) {
        this.status = value;
    }

    @Override
    @Deprecated
    public Future<Void> writeAsync(String text) {
        throw new MuException("Deprecated");
    }

    @Override
    public void write(String text) {
        if (state != OutputState.NOTHING) {
            throw new IllegalStateException("MuResponse.write(String) can only be called once. To send text in multiple chunks" +
                " use MuResponse.sendChunk(String) instead.");
        }
        state = OutputState.FULL_SENT;
        ByteBuffer toSend = charset().encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, toSend.remaining());
        writeBytesREX(rg.writeHeader(status, headers));
        // TODO combine into a single write
        writeBytesREX(toSend);
    }


    void complete(boolean forceDisconnect) throws IOException {
        boolean shouldDisconnect = forceDisconnect || !isKeepAlive;
        try {
            boolean isHead = request.method() == Method.HEAD;
            if (channel.isOpen()) {
                if (state == OutputState.NOTHING) {

                    if (!isHead || !(headers().contains(HeaderNames.CONTENT_LENGTH))) {
                        headers.set(HeaderNames.CONTENT_LENGTH, 0);
                    }
                    if (shouldDisconnect) {
                        headers.add(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                    }
                    ByteBuffer byteBuffer = rg.writeHeader(status, headers);
                    writeBytes(byteBuffer);

                } else if (state == OutputState.STREAMING) {
                    if (!isHead) {
                        if (writer != null) {
                            writer.close();
                        }
                        if (outputStream != null) {
                            outputStream.close();
                        }
                        sendChunkEnd();
                        state = OutputState.STREAMING_COMPLETE;
                    }
                }

                if (!isHead && (headers().contains(HeaderNames.CONTENT_LENGTH))) {
                    long declaredLength = Long.parseLong(headers.get(HeaderNames.CONTENT_LENGTH));
                    long actualLength = this.bytesStreamed;
                    if (declaredLength != actualLength) {
                        shouldDisconnect = true;
                        log.warn("Declared length " + declaredLength + " doesn't equal actual length " + actualLength + " for " + request);
                    }
                }

            }

        } catch (Exception e) {
            log.error("Unexpected exception during complete", e);
            shouldDisconnect = true;
            throw e;
        } finally {
            if (shouldDisconnect) {
                try {
                    channel.close();
                } catch (IOException e) {
                    log.info("Error closing response to client", e);
                }
            }
        }
    }


    private void writeBytesREX(ByteBuffer bytes) {
        try {
            writeBytes(bytes);
        } catch (IOException e) {
            throw new MuException("Error writing to client. They have probably disconnected", e);
        }
    }


    private void throwIfFinished() {
        if (state == OutputState.FULL_SENT || state == OutputState.STREAMING_COMPLETE) {
            throw new IllegalStateException("Cannot write data as response has already completed");
        }
    }

    private void writeBytes(ByteBuffer bytes) throws IOException {
        throwIfFinished();
        int expected = bytes.remaining();
        int written = channel.write(bytes);
        bytesStreamed += written;
        if (written != expected) {
            log.warn("Sent " + written + " bytes but expected to send " + expected);
        }
    }

    @Override
    public void sendChunk(String text) {
        sendChunk(text.getBytes(charset()));
    }

    void sendChunk(byte[] chunkData) {
        if (state == OutputState.NOTHING) {
            startStreaming();
        }

        byte[] size = Integer.toHexString(chunkData.length).getBytes(US_ASCII);
        ByteBuffer bb = ByteBuffer.allocate(chunkData.length + size.length + 4);
        bb.put(size)
            .put((byte) '\r')
            .put((byte) '\n')
            .put(chunkData)
            .put((byte) '\r')
            .put((byte) '\n');
        bb.flip();
        writeBytesREX(bb);
    }

    private static final byte[] LAST_CHUNK = new byte[] { '0', '\r', '\n', '\r', '\n'};
    private void sendChunkEnd() throws IOException {
        writeBytes(ByteBuffer.wrap(LAST_CHUNK));
    }


    @Override
    public void redirect(String url) {
        throw new MuException("Not supported");

    }

    @Override
    public void redirect(URI uri) {
        throw new MuException("Not supported");

    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie.nettyCookie));
    }

    private void startStreaming() {
        if (state != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start streaming when state is " + state);
        }
        state = OutputState.STREAMING;
        if (!headers.contains(HeaderNames.CONTENT_LENGTH)) {
            headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        writeBytesREX(rg.writeHeader(status, headers));
    }

    public OutputStream outputStream() {
        if (this.outputStream == null) {
            startStreaming();
            this.outputStream = new OutputStreamToByteChannelAdapter(channel);
        }
        return this.outputStream;
    }

    public PrintWriter writer() {
        if (this.writer == null) {
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8);
            this.writer = new PrintWriter(os);
        }
        return this.writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return false;
    }
}
