package io.muserver;

import tlschannel.async.AsynchronousTlsChannel;

import javax.ws.rs.RedirectionException;
import javax.ws.rs.core.Response;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MuResponseImpl implements MuResponse {

    private final MuExchangeData data;
    private final AsynchronousTlsChannel tlsChannel;
    private int status = 200;
    private final MuHeaders headers = new MuHeaders();
    private ResponseState state = ResponseState.NOTHING;

    public MuResponseImpl(MuExchangeData data, AsynchronousTlsChannel tlsChannel) {
        this.data = data;
        this.tlsChannel = tlsChannel;
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
    public void write(String text) {
        if (state != ResponseState.NOTHING) throw new IllegalStateException("Cannot write text");

        Charset charset = NettyRequestAdapter.bodyCharset(headers, false);
        ByteBuffer body = charset.encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, body.remaining());
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=" + charset.name());
        }

        var sb = new StringBuilder();
        sb.append(data.httpVersion.version()).append(' ').append(status).append(' ').append("OK").append("\r\n");
        for (Map.Entry<String, List<String>> entry : headers.all().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        sb.append("\r\n");
        var headerBuf = StandardCharsets.US_ASCII.encode(sb.toString());

        try {
            tlsChannel.write(headerBuf).get(10, TimeUnit.SECONDS);
            tlsChannel.write(body).get(10, TimeUnit.SECONDS);
            state = ResponseState.FULL_SENT;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void sendChunk(String text) {

    }

    @Override
    public void redirect(String url) {
        redirect(URI.create(url));
    }

    @Override
    public void redirect(URI uri) {
        throw new RedirectionException(Response.Status.NOT_FOUND, uri);
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

    }

    @Override
    public OutputStream outputStream() {
        return null;
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        return null;
    }

    @Override
    public PrintWriter writer() {
        return null;
    }

    @Override
    public boolean hasStartedSendingData() {
        return false;
    }

    @Override
    public ResponseState responseState() {
        return state;
    }
}
