package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.concurrent.Future;

import static java.nio.charset.StandardCharsets.UTF_8;

class MuResponseImpl implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(MuResponseImpl.class);

    private enum OutputState {
        NOTHING, FULL_SENT, STREAMING, STREAMING_COMPLETE
    }

    private final WritableByteChannel channel;
    private int status = 200;
    private final MuHeaders headers = new MuHeaders();
    private OutputState state = OutputState.NOTHING;
    private final ResponseGenerator rg;

    public MuResponseImpl(WritableByteChannel channel, HttpVersion httpVersion) {
        this.channel = channel;
        rg = new ResponseGenerator(httpVersion);
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

        ByteBuffer toSend = charset().encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, toSend.remaining());
        writeBytes(rg.writeHeader(status, headers));
        // TODO combine into a single write
        writeBytes(toSend);
    }

    private void writeBytes(ByteBuffer bytes) {
        try {
            int expected = bytes.remaining();
            int written = channel.write(bytes);
            if (written != expected) {
                log.warn("Sent " + written + " bytes but expected to send " + expected);
            }
        } catch (IOException e) {
            throw new MuException("Probably disconnected");
        }
    }

    @Override
    public void sendChunk(String text) {
        throw new MuException("Not supported");
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

    @Override
    public void addCookie(Cookie cookie) {
        throw new MuException("Not supported");

    }

    @Override
    public OutputStream outputStream() {
        throw new MuException("Not supported");
    }

    @Override
    public PrintWriter writer() {
        return null;
    }

    @Override
    public boolean hasStartedSendingData() {
        return false;
    }
}
