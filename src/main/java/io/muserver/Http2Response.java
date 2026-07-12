package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.util.Date;

class Http2Response extends BaseResponse {

    private final Http2Stream stream;
    private final FieldBlock fields;

    Http2Response(Http2Stream stream, FieldBlock headers, Mu3Request request) {
        super(request, headers);
        this.fields = headers;
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
        fields.add(0, new FieldLine(HeaderNames.PSEUDO_STATUS, HeaderString.valueOf(Integer.toString(status().code()), HeaderString.Type.VALUE)));

        if (!headers().contains(HeaderNames.DATE)) {
            fields.add(1, new FieldLine((HeaderString) HeaderNames.DATE,
                HeaderString.valueOf(Mutils.toHttpDate(new Date()), HeaderString.Type.VALUE)));
        }

        var headerFragment = new Http2HeadersFrame(
            stream.id, endOfStream, (FieldBlock) headers()
        );
        stream.blockingWrite(headerFragment);
    }

    @Override
    public void sendInformationalResponse(HttpStatus status, @Nullable Headers headers) {
        validateInformationalResponse(status);

        var responseHeaders = copyHeaders(headers);
        responseHeaders.add(0, new FieldLine(HeaderNames.PSEUDO_STATUS, HeaderString.valueOf(Integer.toString(status.code()), HeaderString.Type.VALUE)));

        try {
            stream.blockingWrite(new Http2HeadersFrame(stream.id, false, responseHeaders));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedIOException("Interrupted while writing informational response"));
        } catch (IOException e) {
            throw new UncheckedIOException("Error writing information response", e);
        }
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        if (wrappedOut == null) {
            ContentEncoder responseEncoder = contentEncoder();
            // TODO don't do this here...
            try {
                if (responseState() == ResponseState.NOTHING) {
                    writeStatusAndHeaders(false);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while writing status headers", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            BufferedOutputStream os = new BufferedOutputStream(new Http2DataFrameOutputStream(stream), bufferSize);
            try {
                wrappedOut = responseEncoder == null ? os : responseEncoder.wrapStream(request, this, os);
            } catch (IOException e) {
                throw new UncheckedIOException("Error while setting up output stream", e);
            }
            return wrappedOut;
        } else {
            throw new IllegalStateException("Cannot specify buffer size for response output stream when it has already been created");
        }
    }
}
