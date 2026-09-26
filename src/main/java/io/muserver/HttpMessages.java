package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.muserver.ParseUtils.CRLF;

interface HttpMessageTemp extends Http1ConnectionMsg {
    @Nullable
    HttpVersion getHttpVersion();
    void setHttpVersion(HttpVersion httpVersion);


    FieldBlock headers();

    @Nullable
    BodySize getBodySize();

    void setBodySize(BodySize bodySize);

    BodySize bodyTransferSize();


    static BodySize fixedBodyLength(List<String> cl) {
        if (cl.size() > 1) {
            // 6.3.5 but ignoring the fact there may be multiple with all the same value
            throw new IllegalStateException("Multiple content-length headers");
        }
        // 6.3.5
        long len;
        try {
            len = ParseUtils.parseContentLength(cl.get(0));
        } catch (NumberFormatException e){
            throw new IllegalStateException("Invalid content-length");
        }
        // 6.3.6
        return (len == 0L) ? BodySize.NONE : new BodySize(BodyType.FIXED_SIZE, len);
    }

    static boolean hasFinalChunkedTransferCoding(FieldBlock headers) {
        List<String> values = headers.getAll(HeaderNames.TRANSFER_ENCODING);
        if (values.isEmpty()) return false;

        boolean chunked = false;
        for (String value : values) {
            String[] split = value.split(",", -1);
            for (String item : split) {
                String coding = item.trim();
                if (coding.isEmpty() || chunked) {
                    throw new IllegalStateException("Invalid transfer-encoding");
                }
                for (int i = 0; i < coding.length(); i++) {
                    if (!ParseUtils.isTChar((byte) coding.charAt(i))) {
                        throw new IllegalStateException("Invalid transfer-encoding");
                    }
                }
                chunked = coding.equalsIgnoreCase("chunked");
            }
        }
        if (!chunked) {
            throw new IllegalStateException("The final transfer coding must be chunked");
        }
        return true;
    }

}


class HttpRequestTemp implements HttpMessageTemp {


    private @Nullable Method method;
    private String url = "";
    private @Nullable HttpVersion httpVersion;
    private @Nullable BodySize bodySize;
    private final FieldBlock headers = new FieldBlock();
    private @Nullable HttpException rejectRequest = null;

    public @Nullable Method getMethod() {
        return method;
    }

    public void setMethod(@Nullable Method method) {
        this.method = method;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public @Nullable HttpVersion getHttpVersion() {
        return httpVersion;
    }

    @Override
    public void setHttpVersion(@Nullable HttpVersion httpVersion) {
        this.httpVersion = httpVersion;
    }

    @Override
    public @Nullable BodySize getBodySize() {
        return bodySize;
    }

    @Override
    public void setBodySize(@Nullable BodySize bodySize) {
        this.bodySize = bodySize;
    }

    public @Nullable HttpException getRejectRequest() {
        return rejectRequest;
    }

    public void setRejectRequest(@Nullable HttpException rejectRequest) {
        this.rejectRequest = rejectRequest;
    }

    String normalisedUri() {
        return Mutils.getRelativeUrl(url);
    }

    boolean isWebsocketUpgrade() {
        return headers.containsValue(HeaderNames.UPGRADE, HeaderValues.WEBSOCKET, false);
    }

    @Override
    public FieldBlock headers() {
        return headers;
    }

    @Override
    public BodySize bodyTransferSize() {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length
        var cl = headers.getAll(HeaderNames.CONTENT_LENGTH);
        var isChunked = HttpMessageTemp.hasFinalChunkedTransferCoding(headers);
        // 6.3.3
        if (isChunked && !cl.isEmpty())
            throw new IllegalStateException("A request has transfer-encoding and content-length " + cl);
        // 6.3.4
        if (isChunked) return BodySize.CHUNKED;

        if (cl.isEmpty()) {
            // 6.3.5
            return BodySize.NONE;
        } else {
            return HttpMessageTemp.fixedBodyLength(cl);
        }
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(java.util.Objects.requireNonNull(method).headerBytes());
        out.write(' ');
        out.write(url.getBytes(StandardCharsets.US_ASCII));
        out.write(' ');
        out.write(java.util.Objects.requireNonNull(httpVersion).headerBytes());
        out.write(CRLF);
        headers.writeAsHttp1(out);
        out.write(CRLF);
    }

    static HttpRequestTemp empty() {
        return new HttpRequestTemp();
    }

}

class HttpResponseTemp implements HttpMessageTemp {

    private @Nullable HttpRequestTemp request;
    private @Nullable HttpVersion httpVersion;
    private int statusCode;
    private @Nullable String reason;
    private @Nullable BodySize bodySize;
    private final FieldBlock headers = new FieldBlock();

    boolean isInformational() { return statusCode / 100 == 1; }

    @Override
    public @Nullable HttpVersion getHttpVersion() {
        return httpVersion;
    }

    @Override
    public void setHttpVersion(HttpVersion httpVersion) {
        this.httpVersion = httpVersion;
    }

    @Override
    public FieldBlock headers() {
        return headers;
    }

    @Override
    public @Nullable BodySize getBodySize() {
        return bodySize;
    }

    @Override
    public void setBodySize(BodySize bodySize) {
        this.bodySize = bodySize;
    }

    @Override
    public BodySize bodyTransferSize() {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length

        // 6.3.1
        if (isInformational() || statusCode == 204 || statusCode == 304) return BodySize.NONE;
        var req = request;
        if (req == null) throw new IllegalStateException("Cannot tell the size without the request");
        Method requestMethod = java.util.Objects.requireNonNull(req.getMethod());
        if (requestMethod.isHead()) return BodySize.NONE;

        // 6.3.2
        if (requestMethod == Method.CONNECT) return BodySize.UNSPECIFIED;

        // 6.3.3
        var cl = headers.getAll(HeaderNames.CONTENT_LENGTH);
        var isChunked = headers.hasChunkedBody();
        if (isChunked && !cl.isEmpty()) throw new IllegalStateException("A response has chunked encoding and content-length " + cl);
        // 6.3.4, except this assumes only a single transfer-encoding value
        if (isChunked) return BodySize.CHUNKED;
        if (cl.isEmpty()) {
            // 6.3.8
            return BodySize.UNSPECIFIED;
        } else {
            return HttpMessageTemp.fixedBodyLength(cl);
        }
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(java.util.Objects.requireNonNull(httpVersion).headerBytes());
        out.write(' ');
        out.write(String.valueOf(statusCode).getBytes(StandardCharsets.US_ASCII));
        out.write(' ');
        out.write(java.util.Objects.requireNonNull(reason).getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        headers.writeAsHttp1(out);
        out.write(CRLF);
    }

    static HttpResponseTemp empty() {
        return new HttpResponseTemp();
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setRequest(HttpRequestTemp request) {
        this.request = request;
    }

    public @Nullable HttpRequestTemp getRequest() {
        return request;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public @Nullable String getReason() {
        return reason;
    }
}
