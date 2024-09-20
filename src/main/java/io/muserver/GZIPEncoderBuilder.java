package io.muserver;

import io.muserver.handlers.ResourceType;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A builder for a GZIP Encoder.
 *
 * <p>GZIP is enabled by default so in general you do not need to create an instance of this class.
 * However if you wish to set the encoders with {@link MuServerBuilder#withContentEncoders(List)}
 * you may wish to add a GZIP encoder using this builder.</p>
 */
public class GZIPEncoderBuilder {
    private Set<String> mimeTypesToGzip;
    private long minGzipSize = 1400;
    private int bufferSize = 512;

    /**
     * Sets the mime types which should be gzipped.
     * @param mimeTypesToGzip mime types such as <code>text/plain</code> (note there is no need to specify
     *                        the charset on the mimetypes)
     * @return this builder
     */
    public GZIPEncoderBuilder withMimeTypesToGzip(Set<String> mimeTypesToGzip) {
        Objects.requireNonNull(mimeTypesToGzip, "mimeTypesToGzip must not be null");
        this.mimeTypesToGzip = mimeTypesToGzip;
        return this;
    }

    /**
     * The minimum size of a response, where it is known, before it would be gzipped.
     * <p>The default is 1400 bytes.</p>
     * @param minGzipSize the minimum size
     * @return this builder
     */
    public GZIPEncoderBuilder withMinGzipSize(long minGzipSize) {
        if (minGzipSize < 0) throw new IllegalArgumentException("minGzipSize cannot be negative");
        this.minGzipSize = minGzipSize;
        return this;
    }

    /**
     * The buffer size to use in the {@link java.util.zip.GZIPOutputStream} which defaults to 512.
     * @param bufferSize the buffer size in bytes
     * @return this builder
     */
    public GZIPEncoderBuilder withBufferSize(int bufferSize) {
        if (bufferSize <= 0) throw new IllegalArgumentException("bufferSize must be positive");
        this.bufferSize = bufferSize;
        return this;
    }

    /**
     * Builds the encoder
     * @return a built GZIP Encoder
     */
    public GZIPEncoder build() {
        Set<String> mimes = mimeTypesToGzip != null ? mimeTypesToGzip : ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());
        return new GZIPEncoder(mimes, minGzipSize, bufferSize);
    }

    /**
     * Creates a GZIP encoder builder with default settings.
     * @return a GZIP encoder builder
     */
    public static GZIPEncoderBuilder gzipEncoder() {
        return new GZIPEncoderBuilder();
    }
}