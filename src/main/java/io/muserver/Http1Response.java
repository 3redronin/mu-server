package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Date;

class Http1Response extends BaseResponse implements MuResponse, ResponseInfo {
    private final OutputStream socketOut;
    @Nullable
    private WebsocketConnection websocket;
    @Nullable
    private Long endNanos;
    private boolean shouldCloseConnectionAfterResponse;

    Http1Response(Mu3Request muRequest, OutputStream socketOut) {
        super(muRequest, new FieldBlock());
        this.socketOut = socketOut;
    }

    private byte[] statusAndHeaders() throws IOException {
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Cannot write headers multiple times");
        }
        prepareBodylessResponseHeaders();
        preserveReflectedOriginVary();
        setState(ResponseState.WRITING_HEADERS);

        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(256);
        headerBytes.write(status().http11ResponseLine());
        if (!headers().contains(HeaderNames.DATE)) {
            headers().set("date", Mutils.toHttpDate(new Date()));
        }
        headers.writeAsHttp1(headerBytes);
        headerBytes.write(ParseUtils.CRLF, 0, 2);
        return headerBytes.toByteArray();
    }

    @Override
    public void sendInformationalResponse(HttpStatus status, @Nullable Headers headers) {
        validateInformationalResponse(status);
        try {
            var headerBytes = new ByteArrayOutputStream(256);
            headerBytes.write(status.http11ResponseLine());
            var responseHeaders = copyHeaders(headers);
            if (!responseHeaders.isEmpty()) {
                responseHeaders.writeAsHttp1(headerBytes);
            }
            headerBytes.write(ParseUtils.CRLF, 0, 2);
            socketOut.write(headerBytes.toByteArray());
            socketOut.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Error writing information response", e);
        }
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        if (bufferSize < 0) {
            throw new IllegalArgumentException("Response buffer size cannot be negative");
        }
        if (wrappedOut == null) {
            // A 304 still negotiates metadata for the selected representation.
            ContentEncoder responseEncoder = status().canHaveContent() || status().code() == 304
                ? contentEncoder() : null;

            long fixedLen = headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1);
            if (suppressContent()) {
                if (request.method().isHead() && status().canHaveContent()
                    && fixedLen == -1L && request.httpVersion() == HttpVersion.HTTP_1_1) {
                    headers().set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
                }
            } else if (fixedLen == -1L) {
                if (request.httpVersion() == HttpVersion.HTTP_1_1) {
                    headers().set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
                } else {
                    if (!request.method().isHead() && status().canHaveContent()) {
                        shouldCloseConnectionAfterResponse = true;
                        headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                    }
                }
            }

            try {
                byte[] headerBytes = statusAndHeaders();
                OutputStream rawOut = socketOut;
                if (!suppressContent() && bufferSize > 0) {
                    // A small fixed response needs room only for its headers and body.
                    // Avoid allocating the full default buffer for every tiny response.
                    int capacity = bufferSize;
                    if (fixedLen >= 0 && fixedLen < bufferSize) {
                        capacity = (int) Math.min(bufferSize, headerBytes.length + fixedLen);
                    }
                    rawOut = capacity == 8192
                        ? new BufferedOutputStream(socketOut)
                        : new BufferedOutputStream(socketOut, capacity);
                }
                rawOut.write(headerBytes);
                if (suppressContent()) {
                    // Representation lengths on HEAD/304 do not describe bytes to write.
                    wrappedOut = DiscardingOutputStream.INSTANCE;
                    socketOut.flush();
                } else if (fixedLen != -1L) {
                    wrappedOut = new FixedSizeOutputStream(fixedLen, rawOut);
                } else if (request.httpVersion() == HttpVersion.HTTP_1_1) {
                    wrappedOut = new ChunkedOutputStream(rawOut);
                } else {
                    wrappedOut = new CloseDelimitedOutputStream(rawOut);
                }
                if (responseEncoder != null) {
                    wrappedOut = responseEncoder.wrapStream(request, this, wrappedOut);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Error while setting up output stream", e);
            }
        } else {
            throw new IllegalStateException("Cannot specify buffer size for response output stream when it has already been created");
        }
        return java.util.Objects.requireNonNull(wrappedOut);
    }

    @Override
    void cleanup() throws IOException {
        if (responseState() == ResponseState.NOTHING) {
            // empty response body
            if (!suppressContent()
                && !headers().contains(HeaderNames.CONTENT_LENGTH)) {
                headers().set(HeaderNames.CONTENT_LENGTH, 0L);
            }
            socketOut.write(statusAndHeaders());
            socketOut.flush();
            if (!request.method().isHead() && status().canHaveContent()
                && headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), 0L) > 0L) {
                throw new IOException("Response completed without writing its declared body");
            }
        } else {
            closeWriter();
            OutputStream out = wrappedOut;
            if (out != null) {
                out.close();
            }
        }
        setState(ResponseState.FINISHED);
    }

    @Override
    synchronized boolean setState(ResponseState newState) {
        if (responseState().endState()) {
            return false;
        }
        if (newState.endState()) {
            endNanos = System.nanoTime();
        }
        return super.setState(newState);
    }

    @Override
    public long duration() {
        Long end = endNanos;
        return end == null
            ? MonotonicTime.elapsedMillisSince(request.startNanos())
            : MonotonicTime.elapsedMillis(request.startNanos(), end);
    }

    @Override
    public boolean completedSuccessfully() {
        return responseState().completedSuccessfully() && request.completedSuccessfully();
    }

    @Override
    public Mu3Request request() {
        return request;
    }

    @Override
    public Http1Response response() {
        return this;
    }

    @Override
    public String toString() {
        return status() + " (" + responseState() + ")";
    }

    public void upgrade(WebsocketConnection websocket) {
        this.websocket = websocket;
    }

    @Nullable
    public WebsocketConnection getWebsocket() {
        return websocket;
    }

    boolean shouldCloseConnectionAfterResponse() {
        return shouldCloseConnectionAfterResponse;
    }
}
