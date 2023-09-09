package io.muserver;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.emptyList;

interface MuForm {

    RequestParameters params();

    List<UploadedFile> uploads(String name);

}

class UrlEncodedFormReader implements MuForm, RequestBodyListener {

    private final CompletableFuture<QueryString> query = new CompletableFuture<>();

    // todo make a proper parser so we don't need to build the string first
    private final StringBuilder sb = new StringBuilder();
    private final long readTimeoutMillis;

    UrlEncodedFormReader(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public RequestParameters params() {
        try {
            return query.get(readTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new UncheckedIOException(new InterruptedIOException("Interrupted while reading form"));
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof IOException ioe) throw new UncheckedIOException("Error while reading form body", ioe);
            throw new UncheckedIOException(new IOException("Error while reading form body", cause));
        } catch (TimeoutException e) {
            throw new MuException("Timed out while reading form body", e);
        }
    }

    @Override
    public List<UploadedFile> uploads(String name) {
        return emptyList();
    }

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        // url form encoded is ascii, so no need to worry about boundaries
        CharBuffer decoded = StandardCharsets.US_ASCII.decode(buffer);
        sb.append(decoded);
        doneCallback.onComplete(null);
    }

    @Override
    public void onComplete() {
        var result = QueryString.parse(sb.toString());
        sb.setLength(0);
        query.complete(result);
    }

    @Override
    public void onError(Throwable t) {
        // todo support cancelling?
        query.completeExceptionally(t);
    }
}
