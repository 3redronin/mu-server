package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;

class Http2Response extends BaseResponse {

    private static final int MAX_BATCHED_BODY_BYTES = 8192;
    private static final int MAX_BATCHED_HEADER_BYTES = 4096;

    private final Http2Stream stream;
    private final FieldBlock fields;

    Http2Response(Http2Stream stream, FieldBlock headers, Mu3Request request) {
        super(request, headers);
        this.fields = headers;
        this.stream = stream;
    }

    @Override
    void abortAsyncOutput(boolean activeWrite) {
        if (activeWrite || hasStartedSendingData()) {
            setState(ResponseState.ERRORED);
            stream.abortOutput(new IOException("Asynchronous HTTP/2 output was cancelled"));
        }
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
        setState(ResponseState.FINISHED);
    }

    private void writeStatusAndHeaders(boolean endOfStream) throws InterruptedException, IOException {
        stream.blockingWrite(prepareStatusAndHeaders(endOfStream));
    }

    private Http2HeadersFrame prepareStatusAndHeaders(boolean endOfStream) {
        if (responseState() != ResponseState.NOTHING) {
            throw new IllegalStateException("Cannot write headers multiple times");
        }
        prepareBodylessResponseHeaders();
        setState(ResponseState.WRITING_HEADERS);
        fields.add(0, new FieldLine(HeaderNames.PSEUDO_STATUS, HeaderString.valueOf(Integer.toString(status().code()), HeaderString.Type.VALUE)));

        if (!headers().contains(HeaderNames.DATE)) {
            fields.add(1, new FieldLine((HeaderString) HeaderNames.DATE,
                HttpDateCache.now()));
        }

        return new Http2HeadersFrame(
            stream.id, endOfStream, (FieldBlock) headers()
        );
    }

    @Override
    protected void writeCompleteResponse(byte[] bytes) {
        if (responseState() != ResponseState.NOTHING || wrappedOut != null || suppressContent()
            || bytes.length > MAX_BATCHED_BODY_BYTES) {
            super.writeCompleteResponse(bytes);
            return;
        }
        ContentEncoder encoder = contentEncoder();
        if (encoder != null || !headersFitBatch()) {
            try (OutputStream out = createOutputStream(8192, encoder)) {
                out.write(bytes);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        Http2HeadersFrame headers = prepareStatusAndHeaders(false);
        // Match the closed output stream left by write(String), including cleanup.
        wrappedOut = DiscardingOutputStream.CLOSED;
        try {
            stream.blockingWrite(new Http2ResponseFrame(headers, true, bytes, 0, bytes.length));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedIOException("Interrupted while writing response"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean headersFitBatch() {
        // Bound the extra serialization buffer, including per-field overhead.
        // Large header blocks retain their existing fragmented transport writes.
        long remaining = MAX_BATCHED_HEADER_BYTES;
        for (FieldLine field : fields.lineIterator()) {
            remaining -= 32L + field.name().length() + field.value().length();
            if (remaining < 0) return false;
        }
        return true;
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
        if (bufferSize < 0) {
            throw new IllegalArgumentException("Response buffer size cannot be negative");
        }
        ensureOutputOpen();
        if (wrappedOut == null) {
            // A 304 still negotiates metadata for the selected representation.
            ContentEncoder responseEncoder = status().canHaveContent() || status().code() == 304
                ? contentEncoder() : null;
            return createOutputStream(bufferSize, responseEncoder);
        } else {
            throw new IllegalStateException("Cannot specify buffer size for response output stream when it has already been created");
        }
    }

    private OutputStream createOutputStream(int bufferSize, @Nullable ContentEncoder responseEncoder) {
        // TODO don't do this here...
        try {
            if (responseState() == ResponseState.NOTHING) {
                writeStatusAndHeaders(suppressContent());
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while writing status headers", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        OutputStream os = suppressContent() ? new DiscardingOutputStream()
            : new Http2DataFrameOutputStream(stream);
        if (!suppressContent() && bufferSize > 0) {
            os = new CloseGuardedBufferedOutputStream(os, bufferSize);
        }
        try {
            OutputStream encoded = responseEncoder == null ? os : responseEncoder.wrapStream(request, this, os);
            wrappedOut = encoded == null ? null : responseEncoder == null ? encoded : new CloseGuardedOutputStream(encoded);
        } catch (IOException e) {
            throw new UncheckedIOException("Error while setting up output stream", e);
        }
        return java.util.Objects.requireNonNull(wrappedOut);
    }
}
