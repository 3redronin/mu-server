package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class Http2Exception extends Exception {
    private final Http2Level errorType;
    private final Http2ErrorCode errorCode;
    private final int streamId;

    /**
     * Creates a connection error
     * @param errorCode the code
     * @param message optional message
     */
    Http2Exception(@NotNull Http2ErrorCode errorCode, @Nullable String message) {
        this(Http2Level.CONNECTION, errorCode, message, 0);
    }

    /**
     * Creates a connection error if stream ID is 0; otherwise creates a stream error
     * @param errorCode the code
     * @param message optional message
     * @param streamId the stream ID, or 0 for connection errors
     */
    Http2Exception(@NotNull Http2ErrorCode errorCode, @Nullable String message, int streamId) {
        this(streamId == 0 ? Http2Level.CONNECTION : Http2Level.STREAM, errorCode, message, streamId);
    }
    private Http2Exception(@NotNull Http2Level errorType, @NotNull Http2ErrorCode errorCode, @Nullable String message, int streamId) {
        super(message != null ? message : errorType + " " + errorCode);
        this.errorType = errorType;
        this.errorCode = errorCode;
        this.streamId = streamId;
    }



    @NotNull
    public Http2Level errorType() {
        return errorType;
    }

    @NotNull
    public Http2ErrorCode errorCode() {
        return errorCode;
    }

    public int streamId() {
        return streamId;
    }
}

enum Http2Level {
    CONNECTION, STREAM
}

enum Http2ErrorCode {
    NO_ERROR (0x00),
    PROTOCOL_ERROR (0x01),
    INTERNAL_ERROR (0x02),
    FLOW_CONTROL_ERROR (0x03),
    SETTINGS_TIMEOUT (0x04),
    STREAM_CLOSED (0x05),
    FRAME_SIZE_ERROR (0x06),
    REFUSED_STREAM (0x07),
    CANCEL (0x08),
    COMPRESSION_ERROR (0x09),
    CONNECT_ERROR (0x0a),
    ENHANCE_YOUR_CALM (0x0b),
    INADEQUATE_SECURITY (0x0c),
    HTTP_1_1_REQUIRED (0x0d);
    private final int code;

    Http2ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Http2ErrorCode fromCode(int code) {
        for (Http2ErrorCode errorCode : values()) {
            if (errorCode.code() == code) {
                return errorCode;
            }
        }
        return null;
    }
}
