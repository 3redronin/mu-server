package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

class Http2Stream implements ResponseInfo {

    private static final Logger log = LoggerFactory.getLogger(Http2Stream.class);


    private enum State {
        /* IDLE, RESERVED_LOCAL, RESERVED_REMOTE, */ OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED


    }
    final int id;
    private final Http2Connection connection;
    final Mu3Request request;
    private final Http2IncomingFlowController incomingFlowControl;

    private final Http2OutgoingFlowController outgoingFlowControl;
    @Nullable
    private Http2Response response;
    private State state;
    private long endTime = 0;
    private final InputStream bodyInputStream;
    private final @Nullable Long declaredRequestBodyLength;
    private long receivedRequestBodyLength;
    Http2Stream(int id, Http2Connection connection, State state, Mu3Request request, Http2IncomingFlowController incomingFlowControl, Http2OutgoingFlowController outgoingFlowControl, InputStream bodyInputStream) {
        this(id, connection, state, request, incomingFlowControl, outgoingFlowControl, bodyInputStream, request.declaredBodySize().size());
    }

    Http2Stream(int id, Http2Connection connection, State state, Mu3Request request, Http2IncomingFlowController incomingFlowControl, Http2OutgoingFlowController outgoingFlowControl, InputStream bodyInputStream, @Nullable Long declaredRequestBodyLength) {
        this.id = id;
        this.connection = connection;
        this.state = state;
        this.request = request;
        this.incomingFlowControl = incomingFlowControl;
        this.outgoingFlowControl = outgoingFlowControl;
        this.bodyInputStream = bodyInputStream;
        this.declaredRequestBodyLength = declaredRequestBodyLength;
    }

    int maxFrameSize() {
        return connection.maxFrameSize();
    }

    private int currentWritableDataCredit() {
        return Math.max(0, Math.min(outgoingFlowControl.credit(), connection.currentWriteCredit()));
    }

    private boolean waitUntilWritableDataCreditAvailable(long timeout, TimeUnit unit) throws InterruptedException {
        while (true) {
            if (outgoingFlowControl.terminated() || !canSendFrames()) {
                return false;
            }
            int streamCredit = outgoingFlowControl.credit();
            int connectionCredit = connection.currentWriteCredit();
            if (streamCredit > 0 && connectionCredit > 0) {
                return true;
            }
            if (streamCredit <= 0) {
                if (!outgoingFlowControl.waitUntilAvailable(1, timeout, unit)) {
                    return false;
                }
            }
            if (connectionCredit <= 0) {
                if (!connection.waitUntilWriteCreditAvailable(1, timeout, unit)) {
                    return false;
                }
            }
        }
    }

    @Override
    public long duration() {
        var end = endTime;
        return (end == 0L ? System.currentTimeMillis() : end) - request.startTime();
    }


    @Override
    public boolean completedSuccessfully() {
        return request.completedSuccessfully() && response.responseState().completedSuccessfully();
    }

    void onWindowUpdate(Http2WindowUpdate windowUpdate) throws Http2Exception {
        outgoingFlowControl.applyWindowUpdate(windowUpdate);
    }

    void applyClientSettingsChange(Http2Settings oldSettings, Http2Settings newSettings) throws Http2Exception {
        outgoingFlowControl.applySettingsChange(oldSettings, newSettings);
    }

    void onReset(Http2ResetStreamFrame rstStream) {
        state = State.CLOSED;
        outgoingFlowControl.terminate();
        if (!response.responseState().endState()) {
            response.setState(ResponseState.CLIENT_CANCELLED);
        }
        request.onClientCancelled();
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).onStreamReset(rstStream);
        }
    }

    void cancel(IOException reason) {
        cancel(reason, true);
    }

    void cancel(IOException reason, boolean refundUnreadData) {
        state = State.CLOSED;
        outgoingFlowControl.terminate();
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).cancel(reason, refundUnreadData);
        }
    }

    boolean canReceiveData() {
        return state == State.OPEN || state == State.HALF_CLOSED_LOCAL;
    }

    boolean canSendFrames() {
        return state == State.OPEN || state == State.HALF_CLOSED_REMOTE;
    }

    void onTrailers(Http2HeadersFrame headersFrame) throws Http2Exception {
        if (!canReceiveData()) {
            state = State.CLOSED;
            throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Invalid state for trailers", id);
        }
        if (!headersFrame.endStream()) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Trailing headers must end the stream", id);
        }
        for (FieldLine line : headersFrame.headers().lineIterator()) {
            HeaderString name = line.name();
            if (name.charAt(0) == ':' || RequestTrailers.isForbiddenTrailerField(name)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "invalid trailer field", id);
            }
        }
        validateRequestBodyLengthAtEnd();
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).onTrailers(headersFrame.headers());
        }
        switch (state) {
            case OPEN:
                state = State.HALF_CLOSED_REMOTE;
                break;
            case HALF_CLOSED_LOCAL:
                state = State.CLOSED;
                break;
            default:
                throw new IllegalStateException("Invalid state for trailers: " + state);
        }
    }

    void onData(int flowControlSize, Http2DataFrame dataFrame) throws Http2Exception {
        // todo: thread safety
        if (!canReceiveData()) {
            state = State.CLOSED;
            throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Invalid state for data", id);
        }

        if (!incomingFlowControl.withdrawIfCan(flowControlSize)) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Not enough flow control credit for stream", id);
        }

        if (bodyInputStream instanceof Http2BodyInputStream) {
            receivedRequestBodyLength += dataFrame.payloadLength();
            if (declaredRequestBodyLength != null && receivedRequestBodyLength > declaredRequestBodyLength) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length does not match received DATA", id);
            }
            if (dataFrame.endStream()) {
                // Validate before making the terminal frame visible to the handler. Otherwise the
                // handler can observe EOF and send a response before this stream error is raised.
                validateRequestBodyLengthAtEnd();
            }
            ((Http2BodyInputStream)bodyInputStream).onData(dataFrame, flowControlSize);
            if (dataFrame.endStream()) {
                switch (state) {
                    case OPEN:
                        state = State.HALF_CLOSED_REMOTE;
                        break;
                    case HALF_CLOSED_LOCAL:
                        state = State.CLOSED;
                        break;
                    default:
                        throw new IllegalStateException("Invalid state for data: " + state);
                }
            }
        } else {
            throw new Http2Exception(Http2ErrorCode.INTERNAL_ERROR, "Received data on a stream with no body", id);
        }
    }

    private void validateRequestBodyLengthAtEnd() throws Http2Exception {
        if (declaredRequestBodyLength != null && receivedRequestBodyLength != declaredRequestBodyLength) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length does not match received DATA", id);
        }
    }

    @Override
    public MuRequest request() {
        return request;
    }

    public BaseResponse response() {
        return response;
    }

    static Http2Stream start(Http2Connection connection, Http2HeadersFrame headerFrame, Http2Settings serverSettings, Http2Settings clientSettings) throws Http2Exception {
        var id = headerFrame.streamId();
        FieldBlock headers = headerFrame.headers();

        var iter = headers.lineIterator().iterator();
        Long cl = null;
        HeaderString authority = null;
        HeaderString host = null;
        Method method = null;
        HeaderString path = null;
        HeaderString scheme = null;
        boolean regularHeadersStarted = false;
        while (iter.hasNext()) {
            FieldLine line = iter.next();
            HeaderString n = line.name();
            boolean pseudoHeader = n.charAt(0) == ':';
            if (pseudoHeader) {
                if (regularHeadersStarted) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "pseudo header after regular header", id);
                }
            } else {
                regularHeadersStarted = true;
                // RFC 9113 §8.2.1: field names MUST be lowercase in HTTP/2
                for (int i = 0; i < n.length(); i++) {
                    char c = n.charAt(i);
                    if (c >= 'A' && c <= 'Z') {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "uppercase field name in HTTP/2 request", id);
                    }
                }
            }
            if (n == HeaderNames.PSEUDO_AUTHORITY) {
                if (authority != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :authority", id);
                authority = line.value();
                iter.remove();
            } else if (n == HeaderNames.PSEUDO_METHOD) {
                if (method != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :method", id);
                try {
                    method = Method.valueOf(line.getValue());
                } catch (IllegalArgumentException e) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "invalid method", id);
                }
                iter.remove();
            } else if (n == HeaderNames.PSEUDO_PATH) {
                if (path != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :path", id);
                path = line.value();
                iter.remove();
            } else if (n == HeaderNames.PSEUDO_SCHEME) {
                if (scheme != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :scheme", id);
                scheme = line.value();
                iter.remove();
            } else if (n == HeaderNames.HOST) {
                if (host != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double host", id);
                host = line.value();
            } else if (n == HeaderNames.CONTENT_LENGTH) {
                long len;
                try {
                    len = Long.parseLong(line.value().toString());
                } catch (NumberFormatException e) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length invalid", id);
                }
                if (len < 0) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length negative", id);
                if (cl != null && len != cl) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "multiple content-length lines", id);
                }
                cl = len;
            } else if (n == HeaderNames.CONNECTION) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "connection", id);
            } else if (n == HeaderNames.TRANSFER_ENCODING) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "transfer-encoding", id);
            } else if (n == HeaderNames.KEEP_ALIVE) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "keep-alive", id);
            } else if (n == HeaderNames.PROXY_CONNECTION) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "proxy-connection", id);
            } else if (n == HeaderNames.UPGRADE) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "upgrade", id);
            } else if (n == HeaderNames.TE) {
                // RFC 9113 §8.2.2: TE header MAY appear but MUST NOT contain any value other than "trailers"
                if (!"trailers".equalsIgnoreCase(line.value().toString())) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "te header with value other than trailers", id);
                }
            } else if (pseudoHeader) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "unexpected pseudo header", id);
            }
        }
        if (method == null || path == null || scheme == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "missing required pseudo header", id);
        }
        // RFC 9113 §8.3.1: the :path pseudo-header field MUST NOT be empty
        if (path.length() == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "empty :path pseudo-header", id);
        }
        if (authority == null) {
            // TODO: use this somehow
            authority = host;
        } else if (host == null) {
            headers.add(HeaderNames.HOST, authority);
        }

        var cookies = new ArrayList<String>(2);
        cookies.addAll(headers.getAll(HeaderNames.COOKIE));
        if (cookies.size() > 1) {
            headers.set(HeaderNames.COOKIE, String.join("; ", cookies));
        }

        BodySize bodySize;
        if (headerFrame.endStream()) {
            bodySize = BodySize.NONE;
        } else if (cl != null) {
            bodySize = cl == 0L ? BodySize.NONE : new BodySize(BodyType.FIXED_SIZE, cl);
        } else {
            bodySize = BodySize.UNSPECIFIED;
        }

        var relativeUrl = Mutils.getRelativeUrl(path.toString());
        var serverUri = connection.creator.uri().resolve(relativeUrl);
        var requestUri = Headtils.getUri(log, headers, relativeUrl, serverUri);

        var outgoingFlowControl = new Http2OutgoingFlowController(id, clientSettings.initialWindowSize);
        var incomingFlowControl = new Http2IncomingFlowController(id, serverSettings.initialWindowSize);

        InputStream body = bodySize == BodySize.NONE ? EmptyInputStream.INSTANCE : new Http2BodyInputStream(
            connection.server.requestIdleTimeoutMillis(),
            read -> {
                var update = incomingFlowControl.incrementCredit(read);
                if (update > 0) {
                    connection.write(new Http2WindowUpdate(id, update));
                }
                connection.creditAvailable(read);
            },
            connection
        );
        var request = new Mu3Request(connection, method, requestUri, serverUri, HttpVersion.HTTP_2, headers, bodySize, body);

        if (headerFrame.endStream() && cl != null && cl != 0L) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length does not match received DATA", id);
        }


        var state = headerFrame.endStream() ? State.HALF_CLOSED_REMOTE : State.OPEN;
        Http2Stream stream = new Http2Stream(id, connection, state, request, incomingFlowControl, outgoingFlowControl, body, cl);
        stream.response = new Http2Response(stream, new FieldBlock(), request);
        request.setResponse(stream.response);
        return stream;
    }


    void cleanup() throws IOException, InterruptedException {
        try {
            request.cleanup();
            response.cleanup();
        } finally {
            endTime = System.currentTimeMillis();
        }
    }

    void blockingWriteData(byte[] payload, int offset, int length) throws IOException, InterruptedException {
        int remaining = length;
        int frameOffset = offset;
        while (remaining > 0) {
            if (!waitUntilWritableDataCreditAvailable(1, TimeUnit.HOURS)) {
                if (outgoingFlowControl.terminated() || !canSendFrames()) {
                    throw new IOException("Stream closed while waiting for flow control credit");
                }
                throw new IOException("Timed out waiting for flow control credit");
            }
            int frameLength = Math.min(Math.min(remaining, maxFrameSize()), currentWritableDataCredit());
            if (frameLength <= 0) {
                continue;
            }
            blockingWrite(new Http2DataFrame(id, false, payload, frameOffset, frameLength));
            frameOffset += frameLength;
            remaining -= frameLength;
        }
    }

    /**
     * Writes a frame, blocking if needed until there is enough flow control credit.
     */
    void blockingWrite(LogicalHttp2Frame frame) throws IOException, InterruptedException {

        // DATA frames are subject to flow control and can only be sent when a stream is in the "open" or "half-closed (remote)" state

        // todo: synchronise access to the state
        if (state == State.HALF_CLOSED_LOCAL) {
            if (!(frame instanceof Http2WindowUpdate) && !(frame instanceof Http2ResetStreamFrame)) {
                throw new IllegalStateException("Cannot send data after the stream has been half closed locally. Tried to send " + frame);
            }
        } else if (state == State.CLOSED) {
            throw new IllegalStateException("Cannot send data after the stream has been closed. Tried to send " + frame);
        }

        // todo: use a proper timeout
        int neededCredit = frame.flowControlSize();
        if (neededCredit != 0) {
            if (!outgoingFlowControl.waitUntilWithdraw(neededCredit, 1, TimeUnit.HOURS)) {
                if (outgoingFlowControl.terminated() || !canSendFrames()) {
                    throw new IOException("Stream closed while waiting for flow control credit");
                }
                throw new IOException("Timed out waiting for flow control credit");
            }
        }
        WriteTask writeTask = new WriteTask(frame, true);
        connection.write(writeTask);
        if (frame.endStream()) {
            if (state == State.OPEN) {
                state = State.HALF_CLOSED_LOCAL;
            } else if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED;
            }
        } else if (frame instanceof Http2ResetStreamFrame) {
            state = State.CLOSED;
        }
        writeTask.await(2, TimeUnit.HOURS);
    }

    @Override
    public String toString() {
        Http2Response resp = response;
        return resp == null ? "Uninitialized respons" : resp.status() + " (" + resp.responseState() + ")";
    }

}

/**
 * An HTTP2 frame, where continuations are treated together as a single frame
 */
interface LogicalHttp2Frame {
    void writeTo(Http2Peer connection, OutputStream out) throws IOException;
    default int flowControlSize() {
        return 0;
    }
    default boolean endStream() { return false;}
}
