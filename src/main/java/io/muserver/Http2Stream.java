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

    final int id;
    private final Http2Connection connection;
    final Mu3Request request;

    @Nullable
    private Http2Response response;
    // Reader-owned monotonic fences. They prevent frames following END_STREAM or
    // RST_STREAM on the wire from reaching the body before the coordinator applies
    // the corresponding command.
    private boolean remoteEndStreamRead;
    private boolean peerResetRead;
    // A monotonic published fence used to stop input delivery and to avoid
    // starting new response work after any thread has initiated a reset.
    private volatile boolean resetInitiated;
    private volatile boolean applicationExchangeEnded;
    private volatile boolean protocolStateClosed;
    // Writer-published immediately before END_STREAM output so the reader never
    // lags behind a frame that the peer can already have observed.
    private volatile boolean localEndStreamPublished;
    private long endTime = 0;
    private final InputStream bodyInputStream;
    private final @Nullable Long declaredRequestBodyLength;
    private long receivedRequestBodyLength;
    Http2Stream(int id, Http2Connection connection, Http2StreamState state, Mu3Request request, InputStream bodyInputStream) {
        this(id, connection, state, request, bodyInputStream, request.declaredBodySize().size());
    }

    Http2Stream(int id, Http2Connection connection, Http2StreamState state, Mu3Request request, InputStream bodyInputStream, @Nullable Long declaredRequestBodyLength) {
        this.id = id;
        this.connection = connection;
        this.remoteEndStreamRead = !state.canReceiveEndStream();
        this.request = request;
        this.bodyInputStream = bodyInputStream;
        this.declaredRequestBodyLength = declaredRequestBodyLength;
    }

    int maxFrameSize() {
        return connection.maxFrameSize();
    }

    @Override
    public long duration() {
        var end = endTime;
        return (end == 0L ? System.currentTimeMillis() : end) - request.startTime();
    }


    @Override
    public boolean completedSuccessfully() {
        return request.completedSuccessfully() && requiredResponse().responseState().completedSuccessfully();
    }

    void recordPeerResetFromReader() {
        peerResetRead = true;
        resetInitiated = true;
    }

    void recordLocalResetFromReader() {
        resetInitiated = true;
    }

    boolean peerResetWasRead() {
        return peerResetRead;
    }

    void onProtocolResetApplied() {
        resetInitiated = true;
    }

    boolean resetWasInitiated() {
        return resetInitiated;
    }

    void onApplicationExchangeEnded() {
        applicationExchangeEnded = true;
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).discardRemaining();
        }
    }

    boolean applicationExchangeEnded() {
        return applicationExchangeEnded;
    }

    void onProtocolStateClosed() {
        protocolStateClosed = true;
    }

    void onLocalEndStreamPublished() {
        localEndStreamPublished = true;
    }

    boolean protocolStateClosed() {
        return protocolStateClosed;
    }

    boolean countsTowardsMaxConcurrentStreams() {
        return !protocolStateClosed
            && !peerResetRead
            && !(remoteEndStreamRead && localEndStreamPublished);
    }

    void onProtocolStreamRetired() {
        connection.removeProtocolStream(this);
    }

    /**
     * Applies the I/O-safe application effects after the coordinator closes the
     * protocol stream. This wakes body or async waiters and does not invoke
     * completion listeners or other application callbacks.
     */
    void applyPeerReset(Http2ResetStreamFrame rstStream) {
        onProtocolResetApplied();
        Http2Response currentResponse = requiredResponse();
        if (!currentResponse.responseState().endState()) {
            currentResponse.setState(ResponseState.CLIENT_CANCELLED);
        }
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).onStreamReset(rstStream);
        }
        // This only completes the private future that the handler task is waiting on.
        // Completion listeners remain on that application task.
        request.onClientCancelled();
    }

    void cancel(IOException reason) {
        cancel(reason, true);
    }

    void cancel(IOException reason, boolean refundUnreadData) {
        resetInitiated = true;
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).cancel(reason, refundUnreadData);
        }
    }

    boolean canReceiveData() {
        return !remoteEndStreamRead && !resetInitiated;
    }

    void onTrailers(Http2HeadersFrame headersFrame) throws Http2Exception {
        if (!canReceiveData()) {
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
        recordRemoteEndStreamFromReader();
        connection.remoteEndStream(id);
        if (bodyInputStream instanceof Http2BodyInputStream) {
            ((Http2BodyInputStream) bodyInputStream).onTrailers(headersFrame.headers());
        }
    }

    void onData(int flowControlSize, Http2DataFrame dataFrame) throws Http2Exception {
        if (!canReceiveData()) {
            throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Invalid state for data", id);
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
                recordRemoteEndStreamFromReader();
                // The command is enqueued before EOF becomes visible, so any response awakened
                // by EOF is ordered after the remote transition in the coordinator mailbox.
                connection.remoteEndStream(id);
            }
            ((Http2BodyInputStream)bodyInputStream).onData(dataFrame, flowControlSize);
        } else {
            throw new Http2Exception(Http2ErrorCode.INTERNAL_ERROR, "Received data on a stream with no body", id);
        }
    }

    private void recordRemoteEndStreamFromReader() {
        remoteEndStreamRead = true;
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

    @Override
    public BaseResponse response() {
        return requiredResponse();
    }

    static Http2Stream start(Http2Connection connection, Http2HeadersFrame headerFrame) throws Http2Exception {
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
            if (HeaderNames.PSEUDO_AUTHORITY.equals(n)) {
                if (authority != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :authority", id);
                authority = line.value();
                iter.remove();
            } else if (HeaderNames.PSEUDO_METHOD.equals(n)) {
                if (method != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :method", id);
                try {
                    method = Method.valueOf(line.getValue());
                } catch (IllegalArgumentException e) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "invalid method", id);
                }
                iter.remove();
            } else if (HeaderNames.PSEUDO_PATH.equals(n)) {
                if (path != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :path", id);
                path = line.value();
                iter.remove();
            } else if (HeaderNames.PSEUDO_SCHEME.equals(n)) {
                if (scheme != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double :scheme", id);
                scheme = line.value();
                iter.remove();
            } else if (HeaderNames.HOST.equals(n)) {
                if (host != null) throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "double host", id);
                host = line.value();
            } else if (HeaderNames.CONTENT_LENGTH.equals(n)) {
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
            } else if (HeaderNames.CONNECTION.equals(n)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "connection", id);
            } else if (HeaderNames.TRANSFER_ENCODING.equals(n)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "transfer-encoding", id);
            } else if (HeaderNames.KEEP_ALIVE.equals(n)) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "keep-alive", id);
            } else if (HeaderNames.PROXY_CONNECTION.equals(n)) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "proxy-connection", id);
            } else if (HeaderNames.UPGRADE.equals(n)) {
                // RFC 9113 §8.2.2: connection-specific header fields MUST NOT be used in HTTP/2
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "upgrade", id);
            } else if (HeaderNames.TE.equals(n)) {
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

        // A content-length of zero describes the message body, but it does not close the
        // remote side of the HTTP/2 stream. Keep a private protocol-aware input until
        // END_STREAM while exposing the documented empty body to the application.
        InputStream protocolBody = headerFrame.endStream() ? EmptyInputStream.INSTANCE : new Http2BodyInputStream(
            connection.server.requestIdleTimeoutMillis(),
            read -> connection.returnInboundCredit(id, read, true),
            read -> connection.returnInboundCredit(id, read, false)
        );
        InputStream applicationBody = BodySize.NONE.equals(bodySize)
            ? EmptyInputStream.INSTANCE
            : protocolBody;
        var request = new Mu3Request(
            connection,
            method,
            requestUri,
            serverUri,
            HttpVersion.HTTP_2,
            headers,
            bodySize,
            applicationBody
        );

        if (headerFrame.endStream() && cl != null && cl != 0L) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "content-length does not match received DATA", id);
        }


        var state = headerFrame.endStream() ? Http2StreamState.HALF_CLOSED_REMOTE : Http2StreamState.OPEN;
        Http2Stream stream = new Http2Stream(id, connection, state, request, protocolBody, cl);
        stream.response = new Http2Response(stream, new FieldBlock(), request);
        request.setResponse(stream.response);
        return stream;
    }


    void cleanup() throws IOException, InterruptedException {
        try {
            request.cleanup();
            requiredResponse().cleanup();
        } finally {
            endTime = System.currentTimeMillis();
        }
    }

    void abandonApplicationExchange() {
        try {
            request.cleanup();
        } finally {
            endTime = System.currentTimeMillis();
        }
    }

    private Http2Response requiredResponse() {
        return java.util.Objects.requireNonNull(response, "The HTTP/2 response has not been initialized");
    }

    void blockingWriteData(byte[] payload, int offset, int length) throws IOException, InterruptedException {
        int remaining = length;
        int frameOffset = offset;
        while (remaining > 0) {
            int frameLength = Math.min(remaining, maxFrameSize());
            blockingWrite(new Http2DataFrame(id, false, payload, frameOffset, frameLength));
            frameOffset += frameLength;
            remaining -= frameLength;
        }
    }

    /**
     * Writes a frame, blocking if needed until there is enough flow control credit.
     */
    void blockingWrite(LogicalHttp2Frame frame) throws IOException, InterruptedException {
        WriteTask writeTask = new WriteTask(frame, true);
        connection.write(writeTask);
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
    default int streamId() {
        return 0;
    }
    default int flowControlSize() {
        return 0;
    }
    default boolean endStream() { return false;}
}
