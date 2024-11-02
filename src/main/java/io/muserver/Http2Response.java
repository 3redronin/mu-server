package io.muserver;

import java.io.OutputStream;
import java.util.Date;

public class Http2Response extends BaseResponse {

    private final Http2Stream stream;
    private final Mu3Request request;

    Http2Response(Http2Stream stream, Headers headers, Mu3Request request) {
        super(headers);
        this.stream = stream;
        this.request = request;
    }

    @Override
    protected void cleanup() throws InterruptedException {
        if (responseState() == ResponseState.NOTHING) {
            // empty response body
            if (!request.method().isHead() && status().canHaveContent() && !headers().contains(HeaderNames.CONTENT_LENGTH)) {
                headers().set(HeaderNames.CONTENT_LENGTH, 0L);
            }
            writeStatusAndHeaders(true);
        } else {
            closeWriter();

        }
        if (!responseState().endState()) {
            setState(ResponseState.FINISHED);
        }
    }

    private void writeStatusAndHeaders(boolean endOfStream) throws InterruptedException {
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Cannot write headers multiple times");
        }
        setState(ResponseState.WRITING_HEADERS);
        headers().set(HeaderNames.PSEUDO_STATUS, Integer.toString(status().code()));

        if (!headers().contains(HeaderNames.DATE)) {
            headers().set("date", Mutils.toHttpDate(new Date()));
        }

        var headerFragment = new Http2HeaderFragment(
            stream.id, false, true, endOfStream, 0, 0, (FieldBlock) headers()
        );
        stream.blockingWrite(headerFragment);

    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        return null;
    }
}
