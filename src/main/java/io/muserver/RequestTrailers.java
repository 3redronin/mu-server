package io.muserver;

final class RequestTrailers {

    private RequestTrailers() {
    }

    static void validate(FieldBlock trailers) {
        for (FieldLine line : trailers.lineIterator()) {
            HeaderString name = line.name();
            if (name.charAt(0) == ':') {
                throw HttpException.badRequest("Trailer fields must not contain pseudo headers");
            }
            if (isForbiddenTrailerField(name)) {
                throw HttpException.badRequest("Invalid trailer field: " + name);
            }
        }
    }

    static boolean isForbiddenTrailerField(HeaderString name) {
        return name == HeaderNames.CONNECTION
            || name == HeaderNames.TRANSFER_ENCODING
            || name == HeaderNames.CONTENT_LENGTH
            || name == HeaderNames.HOST
            || name == HeaderNames.TE
            || name == HeaderNames.UPGRADE
            || name == HeaderNames.CONTENT_TYPE
            || name == HeaderNames.CONTENT_ENCODING
            || name == HeaderNames.CONTENT_RANGE
            || name == HeaderNames.AUTHORIZATION
            || name == HeaderNames.PROXY_AUTHORIZATION;
    }
}


