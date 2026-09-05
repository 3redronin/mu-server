package io.muserver;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

abstract class BaseResponse implements MuResponse {

    protected final Mu3Request request;
    final FieldBlock headers;
    @Nullable
    private volatile PrintWriter writer = null;
    @Nullable
    protected volatile OutputStream wrappedOut;

    private final Object completionListenersLock = new Object();
    @GuardedBy("completionListenersLock")
    private @Nullable Queue<ResponseCompleteListener> completionListeners;
    @Nullable
    @GuardedBy("completionListenersLock")
    private ResponseInfo completionInfo;
    @GuardedBy("completionListenersLock")
    private boolean completionListenersDrained;

    private volatile ResponseState state = ResponseState.NOTHING;

    BaseResponse(Mu3Request request, FieldBlock headers) {
        this.request = request;
        this.headers = headers;
    }

    /** Cancels output that has reached the transport; safe to call from an I/O failure path. */
    void abortAsyncOutput(boolean activeWrite) {
        if (activeWrite || hasStartedSendingData()) {
            setState(ResponseState.ERRORED);
            ((BaseHttpConnection) request.connection()).forceShutdown();
        }
    }

    /**
     * Publishes a state transition. The first terminal state wins so that
     * concurrent cleanup cannot turn a failed exchange into a successful one.
     */
    synchronized boolean setState(ResponseState newState) {
        if (state.endState()) {
            return false;
        }
        state = newState;
        return true;
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
            HeaderString val = StandardCharsets.UTF_8.equals(charset) ? (HeaderString) ContentTypes.TEXT_PLAIN_UTF8 : HeaderString.valueOf("text/plain;charset=" + charset.name(), HeaderString.Type.VALUE);
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
    public void contentType(@Nullable CharSequence contentType) {
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
        if (wrappedOut == null) {
            wrappedOut = outputStream(8192);
        }
        return wrappedOut;
    }

    protected @Nullable ContentEncoder contentEncoder() {
        for (var contentEncoder : request.server().contentEncoders()) {
            var theOne = contentEncoder.prepare(request, this);
            if (theOne) {
                return contentEncoder;
            }
        }
        return null;
    }


    @Override
    public PrintWriter writer() {
        if (writer == null) {
            if (!headers.contains(HeaderNames.CONTENT_TYPE)) {
                headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
            }
            writer = new PrintWriter(outputStream(), false, ensureCharsetSet());
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

    abstract void cleanup() throws IOException, InterruptedException;

    protected void closeWriter() throws IOException {
        PrintWriter w = writer;
        if (w != null) {
            w.close();
        }
        OutputStream os = wrappedOut;
        if (os != null) {
            os.close();
        }
    }

    protected final void validateInformationalResponse(HttpStatus status) {
        if (!status.isInformational()) {
            throw new IllegalArgumentException("Only informational status is allowed but received " + status);
        }
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Informational headers cannot be sent after the main response headers have been sent");
        }
    }

    protected final FieldBlock copyHeaders(@Nullable Headers headers) {
        var copy = new FieldBlock();
        if (headers != null) {
            copy.add(headers);
        }
        return copy;
    }

    @Override
    public abstract void sendInformationalResponse(HttpStatus status, @Nullable Headers headers);

    @Override
    public void addCompletionListener(ResponseCompleteListener listener) {
        if (listener == null) {
            throw new NullPointerException("Null completion listener");
        }
        ResponseInfo completed;
        synchronized (completionListenersLock) {
            completed = completionInfo;
            if (completed == null || !completionListenersDrained) {
                if (completionListeners == null) completionListeners = new ArrayDeque<>();
                completionListeners.add(listener);
                return;
            }
        }
        request.serverImpl().executeResponseCompletionTask(() ->
            request.serverImpl().invokeResponseCompletionListener(listener, completed)
        );
    }

    void notifyCompletionListeners(ResponseInfo completed) {
        synchronized (completionListenersLock) {
            if (completionInfo != null) {
                return;
            }
            completionInfo = completed;
        }

        while (true) {
            ResponseCompleteListener listener;
            synchronized (completionListenersLock) {
                listener = completionListeners == null ? null : completionListeners.poll();
                if (listener == null) {
                    completionListenersDrained = true;
                    completionListeners = null;
                    return;
                }
            }
            request.serverImpl().invokeResponseCompletionListener(listener, completed);
        }
    }
}
