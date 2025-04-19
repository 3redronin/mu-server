package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Date;

class Http1Response extends BaseResponse implements MuResponse, ResponseInfo {
    private final OutputStream socketOut;
    @Nullable
    private WebsocketConnection websocket;
    @Nullable
    private Long endMillis;

    Http1Response(Mu3Request muRequest, OutputStream socketOut) {
        super(muRequest, new FieldBlock());
        this.socketOut = socketOut;
    }

    private void writeStatusAndHeaders() throws IOException {
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Cannot write headers multiple times");
        }
        setState(ResponseState.WRITING_HEADERS);

        socketOut.write(status().http11ResponseLine());
        if (!headers().contains(HeaderNames.DATE)) {
            headers().set("date", Mutils.toHttpDate(new Date()));
        }
        headers.writeAsHttp1(socketOut);
        socketOut.write(ParseUtils.CRLF, 0, 2);
    }

    @Override
    public void sendInformationalResponse(HttpStatus status, @Nullable Headers headers) {
        if (!status.isInformational()) {
            throw new IllegalArgumentException("Only informational status is allowed but received " + status);
        }
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Informational headers cannot be sent after the main response headers have been sent");
        }
        try {
            socketOut.write(status.http11ResponseLine());
            if (headers != null) {
                ((FieldBlock) headers).writeAsHttp1(socketOut);
            }
            socketOut.write(ParseUtils.CRLF, 0, 2);
            socketOut.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Error writing information response", e);
        }
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        if (wrappedOut == null) {
            ContentEncoder responseEncoder = contentEncoder();

            long fixedLen = headers().getLong(HeaderNames.CONTENT_LENGTH.toString(), -1);
            OutputStream rawOut = request.method().isHead() ? DiscardingOutputStream.INSTANCE : socketOut;

            if (fixedLen == -1L) {
                headers().set(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
                wrappedOut = new ChunkedOutputStream(rawOut);
            } else {
                wrappedOut = new FixedSizeOutputStream(fixedLen, rawOut);
            }

            try {
                writeStatusAndHeaders();
                if (responseEncoder != null) {
                    wrappedOut = responseEncoder.wrapStream(request, this, wrappedOut);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Error while setting up output stream", e);
            }
        } else {
            throw new IllegalStateException("Cannot specify buffer size for response output stream when it has already been created");
        }
        return wrappedOut;
    }

    @Override
    void cleanup() throws IOException {
        if (responseState() == ResponseState.NOTHING) {
            // empty response body
            if (!request.method().isHead() && status().canHaveContent() && !headers().contains(HeaderNames.CONTENT_LENGTH)) {
                headers().set(HeaderNames.CONTENT_LENGTH, 0L);
            }
            writeStatusAndHeaders();
            socketOut.flush();
        } else {
            closeWriter();
            OutputStream out = wrappedOut;
            if (out != null) {
                out.close();
            }
        }
        if (!responseState().endState()) {
            setState(ResponseState.FINISHED);
        }
    }

    @Override
    void setState(ResponseState newState) {
        super.setState(newState);
        if (newState.endState()) {
            endMillis = System.currentTimeMillis();
        }
    }

    @Override
    public long duration() {
        long endTime = endMillis != null ? endMillis : System.currentTimeMillis();
        return endTime - request.startTime();
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
}