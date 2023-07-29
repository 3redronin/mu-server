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
    private MuHeaders trailers = null;
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
        Charset charset = setDefaultContentType();
        ByteBuffer body = charset.encode(text);
        headers.set(HeaderNames.CONTENT_LENGTH, body.remaining());
        ByteBuffer headerBuf = headersBuffer(true, headers);
        blockingWrite(body, headerBuf);
        state = ResponseState.FULL_SENT;
    }

    private void blockingWrite(ByteBuffer... buffers) {
        try {
            for (ByteBuffer buffer : buffers) {
                String s = new String(buffer.array());
                System.out.print(">>" + s.replace("\r", "\\r").replace("\n", "\\n\r\n"));

                // TODO use scattering write
                tlsChannel.write(buffer).get(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            state = ResponseState.ERRORED;
        } catch (ExecutionException e) {
            e.printStackTrace();
            state = ResponseState.ERRORED;
        } catch (TimeoutException e) {
            e.printStackTrace();
            state = ResponseState.TIMED_OUT;
        }
    }

    private ByteBuffer headersBuffer(boolean reqLine, MuHeaders headers) {
        var sb = new StringBuilder();
        if (reqLine) {
            sb.append(data.httpVersion.version()).append(' ').append(status).append(' ').append("OK").append("\r\n");
        }
        for (Map.Entry<String, List<String>> entry : headers.all().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        sb.append("\r\n");
        var headerBuf = StandardCharsets.US_ASCII.encode(sb.toString());
        return headerBuf;
    }

    @Override
    public void sendChunk(String text) {
        Charset charset;
        ByteBuffer headersBuffer;
        if (state == ResponseState.NOTHING) {
            charset = setDefaultContentType();
            headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
            headersBuffer = headersBuffer(true, headers);
            state = ResponseState.STREAMING;
        } else if (state == ResponseState.STREAMING) {
            charset = NettyRequestAdapter.bodyCharset(headers, false);
            headersBuffer = null;
        } else {
            throw new IllegalStateException("Cannot send chunks when response state is " + state);
        }
        ByteBuffer body = charset.encode(text);
        var chunkStart = StandardCharsets.US_ASCII.encode(Integer.toHexString(body.remaining()) + "\r\n");
        var chunkEnd = StandardCharsets.US_ASCII.encode("\r\n");
        if (headersBuffer != null) {
            blockingWrite(headersBuffer, chunkStart, body, chunkEnd);
        } else {
            blockingWrite(chunkStart, body, chunkEnd);
        }
    }

    public void endStreaming() {
        boolean sendTrailers = trailers != null && Headtils.getParameterizedHeaderWithValues(data.requestHeaders, HeaderNames.TE)
            .stream().anyMatch(v -> v.value().equalsIgnoreCase("trailers"));
        if (sendTrailers) {
            var trailersBuffer = headersBuffer(false, trailers);
            blockingWrite(StandardCharsets.US_ASCII.encode("0\r\n"), trailersBuffer);
        } else {
            blockingWrite(StandardCharsets.US_ASCII.encode("0\r\n\r\n"));
        }
        state = ResponseState.FINISHED;
    }


    private Charset setDefaultContentType() {
        Charset charset = NettyRequestAdapter.bodyCharset(headers, false);
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=" + charset.name());
        }
        return charset;
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
        return state != ResponseState.NOTHING;
    }

    @Override
    public ResponseState responseState() {
        return state;
    }

    @Override
    public Headers trailers() {
        if (trailers == null) {
            trailers = new MuHeaders();
        }
        return trailers;
    }

}
