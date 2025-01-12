package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * A response body content encoder that compresses response bodies with gzip.
 * @see GZIPEncoderBuilder
 */
public class GZIPEncoder implements ContentEncoder {

    private final Set<String> mimeTypesToGzip;
    private final long minGzipSize;
    private final int bufferSize;

    GZIPEncoder(Set<String> mimeTypesToGzip, long minGzipSize, int bufferSize) {
        this.mimeTypesToGzip = Objects.requireNonNull(mimeTypesToGzip, "mimeTypesToGzip");
        this.minGzipSize = minGzipSize;
        this.bufferSize = bufferSize;
    }

    /**
     * The mime types to be zipped
     * @return the set of applicable mime types
     */
    public Set<String> mimeTypesToGzip() {
        return mimeTypesToGzip;
    }

    /**
     * The minimum response content size (where known) before applying gzip
     * @return the min size
     */
    public long minGzipSize() {
        return minGzipSize;
    }

    /**
     * The buffer size used by the {@link GZIPOutputStream}
     * @return the buffer size
     */
    public int bufferSize() {
        return bufferSize;
    }

    @Override
    public boolean prepare(MuRequest request, MuResponse response) {
        return ContentEncoder.defaultPrepare(request, response, mimeTypesToGzip, minGzipSize, contentCoding());
    }

    @Override
    public String contentCoding() {
        return "gzip";
    }

    @Override
    public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) throws IOException {
        return new GZIPOutputStream(stream, bufferSize, true);
    }

}
