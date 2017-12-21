package ronin.muserver;

import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Set;

class SelectiveHttpContentCompressor extends HttpContentCompressor {

    private final int minimumGzipSize;
    private final Set<CharSequence> contentTypes;

    SelectiveHttpContentCompressor(int minimumGzipSize, Set<CharSequence> contentTypes) {
        this.minimumGzipSize = minimumGzipSize;
        this.contentTypes = contentTypes;
    }

    @Override
    protected Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {
        Integer len = headers.headers().getInt(HttpHeaderNames.CONTENT_LENGTH);
        if (len != null) {
            if (len <= minimumGzipSize) {
                return null;
            }
        }

        String contentType = headers.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || contentTypes.contains(contentType)) {
            return null;
        }

        return super.beginEncode(headers, acceptEncoding);
    }
}
