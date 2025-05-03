package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

class Http2Stream implements ResponseInfo {

    private static final Logger log = LoggerFactory.getLogger(Http2Stream.class);

    private enum State {
        /* IDLE, RESERVED_LOCAL, RESERVED_REMOTE, */ OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED

    }
    final int id;
    private final Http2Connection connection;

    final Mu3Request request;
    private final Http2FlowController incomingFlowControl;
    private final Http2FlowController outgoingFlowControl;
    @Nullable
    private Http2Response response;
    private State state = State.OPEN;
    private long endTime = 0;
    Http2Stream(int id, Http2Connection connection, Mu3Request request, Http2FlowController incomingFlowControl, Http2FlowController outgoingFlowControl) {
        this.id = id;
        this.connection = connection;
        this.request = request;
        this.incomingFlowControl = incomingFlowControl;
        this.outgoingFlowControl = outgoingFlowControl;
    }

    int maxFrameSize() {
        return connection.maxFrameSize();
    }

    @Override
    public long duration() {
        var end = endTime;
        return (end == 0L ? System.currentTimeMillis() : end) - request.startTime();
    }

    public void applyWindowUpdate(Http2WindowUpdate windowUpdate) throws Http2Exception {
        outgoingFlowControl.applyWindowUpdate(windowUpdate);
    }

    @Override
    public boolean completedSuccessfully() {
        return request.completedSuccessfully() && response.responseState().completedSuccessfully();
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
        while (iter.hasNext()) {
            FieldLine line = iter.next();
            HeaderString n = line.name();
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
            }
        }
        if (method == null || path == null || scheme == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "missing required pseudo header", id);
        }
        if (authority == null) {
            // TODO: use this somehow
            authority = host;
        } else if (host == null) {
            headers.add(HeaderNames.HOST, authority);
        }

        BodySize bodySize;
        if (headerFrame.endStream()) {
            bodySize = BodySize.NONE;
        } else if (cl != null) {
            bodySize = cl == 0L ? BodySize.NONE : new BodySize(BodyType.FIXED_SIZE, cl);
        } else {
            bodySize = BodySize.UNSPECIFIED;
        }

        var serverUri = connection.creator.uri().resolve(path.toString());
        var requestUri = Headtils.getUri(log, headers, path.toString(), serverUri);

        InputStream body = bodySize == BodySize.NONE ? EmptyInputStream.INSTANCE : null;
        if (body == null) {
            throw new UnsupportedOperationException("Request bodies");
        }
        var request = new Mu3Request(connection, method, requestUri, serverUri, HttpVersion.HTTP_2, headers, bodySize, body);

        var outgoingFlowControl = new Http2FlowController(id, clientSettings.initialWindowSize);
        var incomingFlowControl = new Http2FlowController(id, serverSettings.initialWindowSize);

        Http2Stream stream = new Http2Stream(id, connection, request, incomingFlowControl, outgoingFlowControl);
        stream.response = new Http2Response(stream, new FieldBlock(), request);
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

    /**
     * Writes a frame, blocking if needed until there is enough flow control credit.
     */
    void blockingWrite(LogicalHttp2Frame frame) throws InterruptedException, IOException {

        // todo: synchronise access to the state
        if (state == State.HALF_CLOSED_LOCAL) {
            if (!(frame instanceof Http2WindowUpdate) && !(frame instanceof Http2ResetStreamFrame)) {
                throw new IllegalStateException("Cannot send data after the stream has been half closed locally. Tried to send " + frame);
            }
        } else if (state == State.CLOSED) {
            throw new IllegalStateException("Cannot send data after the stream has been closed. Tried to send " + frame);
        }

        // todo: use a proper timeout
        outgoingFlowControl.waitUntilWithdraw(frame.flowControlSize(), 1, TimeUnit.HOURS);
        connection.write(frame);
        if (frame.endStream()) {
            if (state == State.OPEN) {
                state = State.HALF_CLOSED_LOCAL;
            } else if (state == State.HALF_CLOSED_REMOTE) {
                state = State.CLOSED;
            }
        } else if (frame instanceof Http2ResetStreamFrame) {
            state = State.CLOSED;
        }
    }

    public void flush() throws IOException {
        connection.flush();
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
