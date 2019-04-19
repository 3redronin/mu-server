package io.muserver;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.Http2Headers;

import java.util.Map;

class Http2To1RequestAdapter implements HttpRequest {
    private final HttpMethod nettyMeth;
    private final String uri;
    private final Http2Headers headers;
    private HttpHeaders http1Headers;

    public Http2To1RequestAdapter(HttpMethod nettyMeth, String uri, Http2Headers headers) {
        this.nettyMeth = nettyMeth;
        this.uri = uri;
        this.headers = headers;
    }

    @Override
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
    public HttpRequest setProtocolVersion(HttpVersion version) {
        throw new IllegalStateException("Can't set stuff");
    }

    @Override
    public HttpVersion getProtocolVersion() {
        return HttpVersion.valueOf("HTTP/2");
    }

    @Override
    public HttpVersion protocolVersion() {
        return HttpVersion.valueOf("HTTP/2");
    }

    @Override
    public HttpHeaders headers() {
        if (http1Headers == null) {
            HttpHeaders adapter = new DefaultHttpHeaders();
            for (Map.Entry<CharSequence, CharSequence> header : headers) {
                CharSequence key = header.getKey();
                if (key.charAt(0) != ':') {
                    adapter.add(key, header.getValue());
                }
            }
            http1Headers = adapter;
        }
        return http1Headers;
    }

    @Override
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
