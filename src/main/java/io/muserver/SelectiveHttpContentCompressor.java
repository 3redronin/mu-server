package io.muserver;

import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

class SelectiveHttpContentCompressor extends HttpContentCompressor {

    private final MuServerBuilder.ServerSettings settings;

    SelectiveHttpContentCompressor(MuServerBuilder.ServerSettings settings) {
        this.settings = settings;
    }

    @Override
    protected Result beginEncode(HttpResponse response, String acceptEncoding) throws Exception {
        String declaredLength = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        String declaredType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (settings.shouldCompress(declaredLength, declaredType)) {
            return super.beginEncode(response, acceptEncoding);
        } else {
            return null;
        }
    }

}
