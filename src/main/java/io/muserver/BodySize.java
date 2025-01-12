package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The declared size of a request or response body
 */
@NullMarked
public class BodySize {

    private final BodyType type;
    @Nullable
    private final Long size;

    /**
     * An empty body
     */
    public static BodySize NONE = new BodySize(BodyType.NONE, 0L);
    /**
     * A chunked body of unknown size
     */
    public static BodySize CHUNKED = new BodySize(BodyType.CHUNKED, null);
    /**
     * An unknown body size which is not chunked, and therefore ends when the connection is closed
     */
    public static BodySize UNSPECIFIED = new BodySize(BodyType.UNSPECIFIED, null);

    BodySize(BodyType type, @Nullable Long size) {
        this.type = type;
        this.size = size;
    }

    /**
     * @return the type of body size declaration
     */
    public BodyType type() {
        return type;
    }

    /**
     * @return the size of the body in bytes if known; or <code>null</code> if unknown (e.g. for a chunked body)
     */
    @Nullable
    public Long size() {
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BodySize bodySize = (BodySize) o;
        return type == bodySize.type && Objects.equals(size, bodySize.size);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, size);
    }

    @Override
    public String toString() {
        return "BodySize{" +
            "type=" + type +
            ", size=" + size +
            '}';
    }
}

