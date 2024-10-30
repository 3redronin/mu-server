package io.muserver;

import java.io.InputStream;
import java.net.URI;

class Http2Stream {

    private enum State {
        IDLE, RESERVED_LOCAL, RESERVED_REMOTE, OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED
    }

    private final int id;
    final Mu3Request request;
    final Http2Response response;
    private State state = State.IDLE;

    Http2Stream(int id, Mu3Request request, Http2Response response) {
        this.id = id;
        this.request = request;
        this.response = response;
    }

    static Http2Stream start(Http2Connection connection, Http2HeaderFragment headerFrame, FieldBlock headers) throws Http2Exception {
        var id = headerFrame.streamId();

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
            authority = host;
        }

        BodySize bodySize;
        if (headerFrame.endStream()) {
            bodySize = BodySize.NONE;
        } else if (cl != null) {
            bodySize = cl == 0L ? BodySize.NONE : new BodySize(BodyType.FIXED_SIZE, cl);
        } else {
            bodySize = BodySize.UNSPECIFIED;
        }

        URI serverUri = URI.create(scheme + "://" + authority + path).normalize();
        URI requestUri = serverUri;
        InputStream body = bodySize == BodySize.NONE ? EmptyInputStream.INSTANCE : null;
        if (body == null) {
            throw new UnsupportedOperationException("Request bodies");
        }
        var request = new Mu3Request(connection, method, requestUri, serverUri, HttpVersion.HTTP_2, headers, bodySize, body);

        var response = new Http2Response(new FieldBlock(), request);
        return new Http2Stream(id, request, response);
    }

}
