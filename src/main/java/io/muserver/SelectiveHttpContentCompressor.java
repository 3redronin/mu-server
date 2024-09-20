package io.muserver;

import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

import static io.muserver.NettyResponseAdaptor.getVaryWithAE;

class SelectiveHttpContentCompressor extends HttpContentCompressor {

    private final ServerSettings settings;

    SelectiveHttpContentCompressor(ServerSettings settings) {
        this.settings = settings;
    }

    @Override
    protected Result beginEncode(HttpResponse response, String acceptEncoding) throws Exception {
        String declaredLength = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        String declaredType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
            return null;
    }

}
