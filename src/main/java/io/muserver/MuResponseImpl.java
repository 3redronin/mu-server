package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.RedirectionException;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import static io.muserver.ContentTypes.TEXT_PLAIN_UTF8;

public class MuResponseImpl implements MuResponse {
    private static final Logger log = LoggerFactory.getLogger(MuResponseImpl.class);

    private final MuExchangeData data;
    private int status = 200;
    private final MuHeaders headers = MuHeaders.responseHeaders();
    MuHeaders trailers = null;
    private ResponseState state = ResponseState.NOTHING;
    private OutputStream outputStream;
    private PrintWriter writer;

    public MuResponseImpl(MuExchangeData data) {
        this.data = data;
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
    public void write(String text) throws IOException {
        if (state != ResponseState.NOTHING) throw new IllegalStateException("Cannot write text");
        if (text == null) text = "";
        Charset charset = setDefaultContentType();
        ByteBuffer body;
        // Note that checking a string length against a byte size is not normally valid but we
        // don't have to be exact here. In a string with multi-byte characters it will just mean
        // sometimes it won't gzip when it should. But it's better than always zipping small strings.
        if (text.length() >= data.server().minimumGzipSize() && prepareForGzip()) {
            byte[] uncompressed = text.getBytes(charset);
            var baos = new ByteArrayOutputStream();
            try (var out = new GZIPOutputStream(baos)) {
                // todo: look at writing to outputStream() instead. Would have lower memory but would lose the content size
                out.write(uncompressed);
            }
            body = ByteBuffer.wrap(baos.toByteArray());
        } else {
            body = charset.encode(text);
        }
        headers.set(HeaderNames.CONTENT_LENGTH, body.remaining());
        blockingWrite(body);
        setState(ResponseState.FULL_SENT);
    }

    private void blockingWrite(ByteBuffer body) throws IOException {
        try {
            data.exchange.write(body).get(this.data.server().settings.responseWriteTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            setState(ResponseState.ERRORED);
            throw new InterruptedIOException("Interrupted while writing response");
        } catch (ExecutionException e) {
            setState(ResponseState.ERRORED);
            Throwable cause = e.getCause();
            throw cause instanceof IOException ? (IOException) cause : new IOException("Exception while writing body", cause);
        } catch (TimeoutException e) {
            setState(ResponseState.TIMED_OUT);
            throw new IOException("Timed out while writing", e);
        }
    }

    private boolean prepareForGzip() {
        var settings = data.acceptor().muServer.settings;
        if (!settings.gzipEnabled()) return false;

        var responseType = headers.contentType();
        if (responseType == null) return false;
        var mime = responseType.getType() + "/" + responseType.getSubtype();
        boolean mimeTypeOk = settings.mimeTypesToGzip().contains(mime);
        if (!mimeTypeOk) return false;

        headers.setOrAddCSVHeader(HeaderNames.VARY, HeaderNames.ACCEPT_ENCODING, false); // TODO change to add if not already there

        if (headers.contains(HeaderNames.CONTENT_ENCODING)) return false; // don't re-encode something

        boolean clientSupports = false;
        for (ParameterizedHeaderWithValue acceptEncoding : data.requestHeaders().acceptEncoding()) {
            if (acceptEncoding.value().equalsIgnoreCase("gzip")) {
                clientSupports = true;
                break;
            }
        }
        if (!clientSupports) return false;
        long contentSize = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), Long.MAX_VALUE);
        if (contentSize < settings.minGzipSize()) return false;

        headers.set(HeaderNames.CONTENT_ENCODING, HeaderValues.GZIP);
        return true;
    }

    ByteBuffer headersBuffer(boolean reqLine, MuHeaders headers) {
        return http1HeadersBuffer(headers, reqLine ? data.newRequest.version() : null, status, "OK");
    }

    static ByteBuffer http1HeadersBuffer(MuHeaders headers, HttpVersion httpVersion, int status, String statusString) {
        var sb = new StringBuilder();
        if (httpVersion != null) {
            sb.append(httpVersion.version()).append(' ').append(status).append(' ').append(statusString).append("\r\n");
        }
        for (Map.Entry<String, List<String>> entry : headers.all().entrySet()) {
            for (String value : entry.getValue()) {
                sb.append(entry.getKey()).append(": ").append(value).append("\r\n");
            }
        }
        sb.append("\r\n");
        return StandardCharsets.US_ASCII.encode(sb.toString());
    }

    @Override
    public void sendChunk(String text) throws IOException {
        PrintWriter w = writer();
        w.write(text);
        w.flush();
    }

    public void closeStreams() throws IOException {

        PrintWriter w = writer;
        if (w != null) {
            writer = null;
            w.close();
        }
        OutputStream os = outputStream;
        if (os != null) {
            outputStream = null;
            os.close();
        }

    }

    void setState(ResponseState newState) {
        if (!this.state.endState()) {
            this.state = newState;
            if (newState.endState()) {
                data.exchange.onResponseCompleted(this);
            }
        }
    }

    private Charset setDefaultContentType() {
        Charset charset = NettyRequestAdapter.bodyCharset(headers, false);
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, charset == StandardCharsets.UTF_8 ? TEXT_PLAIN_UTF8 : "text/plain;charset=" + charset.name());
        }
        return charset;
    }

    @Override
    public void redirect(String url) {
        redirect(URI.create(url));
    }

    @Override
    public void redirect(URI uri) {
        throw new RedirectionException(Response.Status.FOUND, uri);
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
        headers.add(HeaderNames.SET_COOKIE, cookie.toString());
    }

    @Override
    public OutputStream outputStream() {
        return outputStream(4096);
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        throwIfAsync();
        if (this.outputStream == null) {
            if (this.state != ResponseState.NOTHING)
                throw new IllegalStateException("Cannot write to response with state " + state);

            OutputStream adapter = new MuResponseOutputStream();
            if (bufferSize > 0) {
                adapter = new BufferedOutputStream(adapter, bufferSize);
            }
            if (prepareForGzip()) {
                try {
                    adapter = new GZIPOutputStream(adapter, true);
                } catch (IOException e) {
                    throw new UncheckedIOException("Error while starting GZIP compression", e);
                }
            }
            this.outputStream = adapter;
        }
        return this.outputStream;
    }

    ByteBuffer startStreaming() {
        this.state = ResponseState.STREAMING;
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.APPLICATION_OCTET_STREAM);
        }
        if (!headers.contains(HeaderNames.CONTENT_LENGTH)) {
            headers.set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
        }
        return headersBuffer(true, headers);
    }

    @Override
    public PrintWriter writer() {
        if (this.writer == null) {
            Charset charset = setDefaultContentType();
            OutputStreamWriter os = new OutputStreamWriter(outputStream(), charset);
            this.writer = new PrintWriter(os, false);
        }
        return this.writer;
    }

    private void throwIfAsync() {
        //TODO
//        if (data.isAsync()) {
//            throw new IllegalStateException("Cannot use blocking methods when in async mode");
//        }
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

    boolean isChunked() {
        return !headers.contains(HeaderNames.CONTENT_LENGTH);
    }

    public void onException(Throwable cause) {


    }

    void abort(Throwable cause) {
        if (!responseState().endState()) {
            state = ResponseState.ERRORED;
        }
    }

    private class MuResponseOutputStream extends OutputStream {
        private boolean closed = false;

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Writing to a closed stream");
            blockingWrite(ByteBuffer.wrap(b, off, len));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
            }
        }

    }
}
