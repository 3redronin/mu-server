package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

import static java.util.Collections.emptyList;

abstract class BaseResponse implements MuResponse {

    private final Headers headers;
    private volatile PrintWriter writer = null;


    @Nullable
    private ConcurrentLinkedQueue<ResponseCompleteListener> completionListeners = null;

    private ResponseState state = ResponseState.NOTHING;

    BaseResponse(Headers headers) {
        this.headers = headers;
    }

    void setState(@NotNull ResponseState newState) {
        state = newState;
    }

    @Nullable
    private HttpStatus status;

    @Override
    public HttpStatus status() {
        var s = status;
        return s == null ? HttpStatus.OK_200 : s;
    }

    @Override
    public void status(int value) {
        status = HttpStatus.of(value);
    }

    @Override
    public void status(HttpStatus value) {
        if (value == null) throw new NullPointerException("status is null");
        status = value;
    }

    protected Charset ensureCharsetSet() {
        var charset = Headtils.bodyCharset(headers(), false);
        if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
            HeaderString val = charset == StandardCharsets.UTF_8 ? (HeaderString) ContentTypes.TEXT_PLAIN_UTF8 : HeaderString.valueOf("text/plain;charset=" + charset.name(), HeaderString.Type.VALUE);
            headers.set(HeaderNames.CONTENT_TYPE, val);
        }
        return charset;
    }


    @Override
    public void write(String text) {
        var charset = ensureCharsetSet();
        var bytes = text.getBytes(charset);
        headers.set("content-length", bytes.length);
        try (var out = outputStream()) {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        setState(ResponseState.FULL_SENT);
    }

    @Override
    public void sendChunk(String text) {
        var charset = ensureCharsetSet();
        var out = outputStream();
        try {
            out.write(text.getBytes(charset));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void redirect(String url) {
        redirect(URI.create(url));
    }

    @Override
    public void redirect(URI uri) {
        var status = this.status == null ? HttpStatus.FOUND_302 : this.status;
        if (!status.isRedirection()) {
            status(HttpStatus.FOUND_302);
        }
        var ex = new HttpException(status, (String)null);
        ex.responseHeaders().set(HeaderNames.LOCATION, uri.normalize().toString());
        throw ex;
    }

    @Override
    public Headers headers() {
        return headers;
    }

    @Override
    public void contentType(CharSequence contentType) {
        if (contentType == null) {
            headers.remove(HeaderNames.CONTENT_TYPE);
        } else {
            headers.set(HeaderNames.CONTENT_TYPE, contentType);
        }
    }

    @Override
    public void addCookie(Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, cookie.toString());
    }

    @Override
    public OutputStream outputStream() {
        return outputStream(8192);
    }

    @Override
    public PrintWriter writer() {
        if (writer == null) {
            if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
                headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
            }
            writer = new  PrintWriter(outputStream(), false, ensureCharsetSet());
        }
        return writer;
    }

    @Override
    public boolean hasStartedSendingData() {
        return state != ResponseState.NOTHING;
    }

    @Override
    public ResponseState responseState() {
        return state;
    }

    protected abstract void cleanup();

    protected void closeWriter() {
        if (writer != null) {
            writer.close();
        }
    }

    @Override
    public void sendInformationalResponse(HttpStatus status, Headers headers) {

    }

    @Override
    public void addCompletionListener(ResponseCompleteListener listener) {
        if (listener == null) {
            throw new NullPointerException("Null completion listener");
        }
        if (completionListeners == null) {
            completionListeners = new ConcurrentLinkedQueue<>();
        }
        completionListeners.add(listener);
    }

    Iterable<ResponseCompleteListener> completionListeners() {
        var cl = completionListeners;
        return cl == null ? emptyList() : cl;
    }


}
