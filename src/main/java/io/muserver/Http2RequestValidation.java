package io.muserver;

import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;

/** Checks authority semantics that Netty's HTTP/2 decoder does not validate. */
final class Http2RequestValidation {
    private Http2RequestValidation() { }

    /** Must run before Host is replaced with :authority in the shared headers. */
    static String authority(int streamId, Http2Headers headers) throws Http2Exception {
        CharSequence authority = headers.authority();
        if (authority == null) {
            authority = headers.get(HeaderNames.HOST);
        }
        if (authority == null || authority.length() == 0) {
            throw streamError(streamId, PROTOCOL_ERROR, "Request has no authority or Host value");
        }
        for (CharSequence host : headers.getAll(HeaderNames.HOST)) {
            if (!AsciiString.contentEqualsIgnoreCase(authority, host)) {
                throw streamError(streamId, PROTOCOL_ERROR, "Conflicting :authority and Host values");
            }
        }
        return authority.toString();
    }
}
