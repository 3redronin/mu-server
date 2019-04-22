package io.muserver;

import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Set;

class SelectiveHttpContentCompressor extends HttpContentCompressor {

    private final long minimumGzipSize;
    private final Set<String> contentTypes;

    SelectiveHttpContentCompressor(long minimumGzipSize, Set<String> contentTypes) {
        this.minimumGzipSize = minimumGzipSize;
        this.contentTypes = contentTypes;
    }

    @Override
    protected Result beginEncode(HttpResponse response, String acceptEncoding) throws Exception {
        String len = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (len != null && Long.parseLong(len) <= minimumGzipSize) {
            return null;
        }

        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null) {
            return null;
        }
        int i = contentType.indexOf(";");
        if (i > -1) {
            contentType = contentType.substring(0, i);
        }
        if (!contentTypes.contains(contentType.trim())) {
            return null;
        }
        return super.beginEncode(response, acceptEncoding);
    }
}
