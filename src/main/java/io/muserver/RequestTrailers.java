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
        return HeaderNames.CONNECTION.equals(name)
            || HeaderNames.TRANSFER_ENCODING.equals(name)
            || HeaderNames.CONTENT_LENGTH.equals(name)
            || HeaderNames.HOST.equals(name)
            || HeaderNames.TE.equals(name)
            || HeaderNames.UPGRADE.equals(name)
            || HeaderNames.CONTENT_TYPE.equals(name)
            || HeaderNames.CONTENT_ENCODING.equals(name)
            || HeaderNames.CONTENT_RANGE.equals(name)
            || HeaderNames.AUTHORIZATION.equals(name)
            || HeaderNames.PROXY_AUTHORIZATION.equals(name);
    }
}

