package io.muserver;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil;

class Http2To1RequestAdapter implements HttpRequest {
    private final HttpMethod nettyMeth;
    private final String uri;
    private final Http2Headers headers;
    private final int streamId;
    private HttpHeaders http1Headers;

    Http2To1RequestAdapter(int streamId, HttpMethod nettyMeth, String uri, Http2Headers headers) {
        this.streamId = streamId;
        this.nettyMeth = nettyMeth;
        this.uri = uri;
        this.headers = headers;
    }

    @Override
    @Deprecated
    public HttpMethod getMethod() {
        return nettyMeth;
    }

    @Override
    public HttpMethod method() {
        return nettyMeth;
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        throw new IllegalStateException("Can't set stuff");
    }

    @Override
    @Deprecated
    public String getUri() {
        return uri;
    }

    @Override
    public String uri() {
        return uri;
    }

    @Override
    public HttpRequest setUri(String uri) {
        throw new IllegalStateException("Can't set stuff");
    }

    @Override
    public HttpRequest setProtocolVersion(io.netty.handler.codec.http.HttpVersion version) {
        throw new IllegalStateException("Can't set stuff");
    }

    @Override
    @Deprecated
    public io.netty.handler.codec.http.HttpVersion getProtocolVersion() {
        return io.netty.handler.codec.http.HttpVersion.valueOf("HTTP/2.0");
    }

    @Override
    public io.netty.handler.codec.http.HttpVersion protocolVersion() {
        return new io.netty.handler.codec.http.HttpVersion("HTTP/2.0", true);
    }

    @Override
    public HttpHeaders headers() {
        if (http1Headers == null) {
            HttpHeaders adapter = new DefaultHttpHeaders();
            try {
                HttpConversionUtil.addHttp2ToHttpHeaders(streamId, headers, adapter, io.netty.handler.codec.http.HttpVersion.HTTP_1_1, false, true);
            } catch (Http2Exception e) {
                throw new MuException("Error while preparing headers for multipart form upload");
            }
            http1Headers = adapter;
        }
        return http1Headers;
    }

    @Override
    @Deprecated
    public DecoderResult getDecoderResult() {
        return DecoderResult.SUCCESS;
    }

    @Override
    public DecoderResult decoderResult() {
        return DecoderResult.SUCCESS;
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        throw new IllegalStateException("Can't set stuff");
    }
}
