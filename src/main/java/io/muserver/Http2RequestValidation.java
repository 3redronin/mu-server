package io.muserver;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;

/** Checks HTTP/2 authority semantics before application dispatch. */
final class Http2RequestValidation {
    private Http2RequestValidation() { }

    /** Must run before Host is replaced with :authority in the shared headers. */
    static String authority(int streamId, @Nullable CharSequence authority, Headers headers) throws InvalidAuthorityException {
        if (authority == null) {
            authority = headers.get(HeaderNames.HOST);
        }
        if (authority == null || authority.length() == 0) {
            throw new InvalidAuthorityException(streamId, "Request has no authority or Host value");
        }
        String selected = authority.toString();
        for (String host : headers.getAll(HeaderNames.HOST)) {
            if (!selected.equalsIgnoreCase(host)) {
                throw new InvalidAuthorityException(streamId, "Conflicting :authority and Host values");
            }
        }
        return selected;
    }

    static void validateUri(int streamId, String scheme, String authority, String path) throws InvalidAuthorityException {
        URI uri;
        try {
            uri = new URI(scheme + "://" + authority + path);
        } catch (URISyntaxException e) {
            throw new InvalidAuthorityException(streamId, "Invalid request URI");
        }
        if (!authority.equals(uri.getRawAuthority()) || authority.indexOf('@') >= 0) {
            throw new InvalidAuthorityException(streamId, "Invalid request authority");
        }
    }

    /** Authority failures reset the stream without creating an HTTP rejection response. */
    static final class InvalidAuthorityException extends Http2Exception {
        InvalidAuthorityException(int streamId, String message) {
            super(Http2ErrorCode.PROTOCOL_ERROR, message, streamId);
        }
    }
}
