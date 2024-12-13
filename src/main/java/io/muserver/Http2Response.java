package io.muserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Date;

public class Http2Response extends BaseResponse {

    private final Http2Stream stream;

    Http2Response(Http2Stream stream, Headers headers, Mu3Request request) {
        super(request, headers);
        this.stream = stream;
    }

    @Override
    protected void cleanup() throws IOException, InterruptedException {
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

    private void writeStatusAndHeaders(boolean endOfStream) throws InterruptedException, IOException {
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
        if (wrappedOut == null) {
            // TODO don't do this here...
            try {
                if (responseState() == ResponseState.NOTHING) {
                    writeStatusAndHeaders(false);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            BufferedOutputStream os = new BufferedOutputStream(new Http2DataFrameOutputStream(stream), bufferSize);
            wrappedOut = os;
            return os;
        } else {
            throw new IllegalStateException("Cannot specify buffer size for response output stream when it has already been created");
        }
    }
}
