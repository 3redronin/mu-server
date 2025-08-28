package io.muserver;

import org.jspecify.annotations.Nullable;

class Http2Exception extends Exception {
    private final Http2Level errorType;
    private final Http2ErrorCode errorCode;
    private final int streamId;

    /**
     * Creates a connection error
     * @param errorCode the code
     * @param message optional message
     */
    Http2Exception(Http2ErrorCode errorCode, @Nullable String message) {
        this(Http2Level.CONNECTION, errorCode, message, 0);
    }

    /**
     * Creates a connection error if stream ID is 0; otherwise creates a stream error
     * @param errorCode the code
     * @param message optional message
     * @param streamId the stream ID, or 0 for connection errors
     */
    Http2Exception(Http2ErrorCode errorCode, @Nullable String message, int streamId) {
        this(streamId == 0 ? Http2Level.CONNECTION : Http2Level.STREAM, errorCode, message, streamId);
    }
    private Http2Exception(Http2Level errorType, Http2ErrorCode errorCode, @Nullable String message, int streamId) {
        super(message != null ? message : errorType + " " + errorCode);
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.streamId = streamId;
    }

    public Http2Level errorType() {
        return errorType;
    }

    public Http2ErrorCode errorCode() {
        return errorCode;
    }

    public int streamId() {
        return streamId;
    }

    public LogicalHttp2Frame toFrame() {
        if (errorType == Http2Level.CONNECTION) {
            return new Http2GoAway(streamId, errorCode.code(), null);
        } else {
            return new Http2ResetStreamFrame(streamId, errorCode().code());
        }
    }
}

enum Http2Level {
    CONNECTION, STREAM
}

enum Http2ErrorCode {
    /**
     * Indicates no error has occurred.
     * This is used when a frame is deliberately terminated, such as when a GOAWAY is sent prior to shutdown.
     */
    NO_ERROR (0x00),

    /**
     * The endpoint detected a violation of the protocol. This is a generic error when a more specific error code is not applicable.
     */
    PROTOCOL_ERROR (0x01),

    /**
     * The endpoint encountered an unexpected internal error.
     * This indicates a bug in the implementation or problems with the environment or used when no other error code is appropriate.
     */
    INTERNAL_ERROR (0x02),

    /**
     * The endpoint detected that its peer has violated the flow-control protocol.
     * This occurs when the advertised flow-control window is exceeded.
     */
    FLOW_CONTROL_ERROR (0x03),

    /**
     * The endpoint sent a SETTINGS frame but did not receive a response in a timely manner.
     * This occurs when the SETTINGS ACK frame is not received within a reasonable time.
     */
    SETTINGS_TIMEOUT (0x04),

    /**
     * The endpoint received a frame after a stream was half-closed.
     * This occurs when a frame other than WINDOW_UPDATE, PRIORITY, or RST_STREAM is received after the stream is half-closed.
     */
    STREAM_CLOSED (0x05),

    /**
     * The endpoint received a frame with an invalid size.
     * This includes frames that are too large, have an invalid length, or violate size limits for specific frame types.
     */
    FRAME_SIZE_ERROR (0x06),

    /**
     * The endpoint refuses the stream prior to performing any application processing.
     * This is used when a requested stream cannot be processed due to resource constraints or other reasons.
     */
    REFUSED_STREAM (0x07),

    /**
     * The endpoint indicates that the stream or connection is no longer needed.
     * This is used when an operation is intentionally cancelled.
     */
    CANCEL (0x08),

    /**
     * The endpoint is unable to maintain the header compression context, possibly due to a malformed header block or a problem decoding HPACK-encoded headers.
     */
    COMPRESSION_ERROR (0x09),

    /**
     * The connection established in response to a CONNECT request was reset or abnormally closed.
     * This occurs when a TCP connection established by a CONNECT request fails or is terminated.
     */
    CONNECT_ERROR (0x0a),

    /**
     * The endpoint detected that its peer is exhibiting behavior likely to lead to service degradation, such as excessive request rate. This is a signal to slow down or back off.
     */
    ENHANCE_YOUR_CALM (0x0b),

    /**
     * The endpoint requires that additional protocol-level security be used.
     * This occurs when the underlying transport security mechanisms are deemed insufficient.
     */
    INADEQUATE_SECURITY (0x0c),

    /**
     * The endpoint refuses to process the request using HTTP/2 and requires that it be retried over HTTP/1.1.
     */
    HTTP_1_1_REQUIRED (0x0d);
;
    private final int code;

    Http2ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static @Nullable Http2ErrorCode fromCode(int code) {
        for (Http2ErrorCode errorCode : values()) {
            if (errorCode.code() == code) {
                return errorCode;
            }
        }
        return null;
    }
}
