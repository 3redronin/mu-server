package io.muserver.rest;

import io.muserver.openapi.SchemaObject;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Provides the context that a {@link SchemaObject} is in when customizing the schema in a {@link SchemaObjectCustomizer}
 */
public class SchemaObjectCustomizerContext {

    private final SchemaObjectCustomizerTarget target;
    private final Class<?> type;
    private final @Nullable Type parameterizedType;
    private final @Nullable Object resource;
    private final Method method;
    private final @Nullable String parameter;
    private final MediaType mediaType;
    private final @Nullable String eventName;
    private final @Nullable Class<?> payloadType;
    private final @Nullable MediaType payloadMediaType;
    private final @Nullable String parameterLocation;

    SchemaObjectCustomizerContext(SchemaObjectCustomizerTarget target, Class<?> type, @Nullable Type parameterizedType, @Nullable Object resource, Method method, @Nullable String parameter, MediaType mediaType) {
        this(target, type, parameterizedType, resource, method, parameter, mediaType, null);
    }

    SchemaObjectCustomizerContext(SchemaObjectCustomizerTarget target, Class<?> type, @Nullable Type parameterizedType, @Nullable Object resource, Method method, @Nullable String parameter, MediaType mediaType, @Nullable String parameterLocation) {
        this(target, type, parameterizedType, resource, method, parameter, mediaType, parameterLocation, null, null, null);
    }

    SchemaObjectCustomizerContext(SchemaObjectCustomizerTarget target, Class<?> type, @Nullable Type parameterizedType,
        @Nullable Object resource, Method method, @Nullable String parameter, MediaType mediaType, @Nullable String parameterLocation,
        @Nullable String eventName, @Nullable Class<?> payloadType, @Nullable MediaType payloadMediaType) {
        this.eventName = eventName;
        this.payloadType = payloadType;
        this.payloadMediaType = payloadMediaType;
        this.parameterLocation = parameterLocation;
        this.target = requireNonNull(target, "target");
        this.type = requireNonNull(type, "type");
        this.parameterizedType = parameterizedType;
        this.resource = resource;
        this.method = method;
        this.parameter = parameter;
        this.mediaType = requireNonNull(mediaType, "mediaType");
    }

    /**
     * @return The type of object being described, e.g. a request body or response body.
     */
    public SchemaObjectCustomizerTarget target() {
        return target;
    }

    /**
     * For normal resources, this is the instance passed to the {@link RestHandlerBuilder}. Note that for sub-resources
     * returned by a sub-resource-locator, this will be null.
     * @return The rest resource that creates or consumes the object being described
     */
    public @Nullable Object resource() {
        return resource;
    }

    /**
     * @return The java method that builds or consumes the object being described
     */
    public Optional<Method> methodHandle() {
        return Optional.ofNullable(method);
    }

    /**
     * @return The parameter name for ordinary and form parameters.
     */
    public Optional<String> parameterName() {
        return Optional.ofNullable(parameter);
    }

    /** @return The parameter location (query, path, matrix, header, cookie or form), when applicable. */
    public Optional<String> parameterLocation() {
        return Optional.ofNullable(parameterLocation);
    }

    /**
     * @return The java type of the object being described
     */
    public Class<?> type() {
        return type;
    }

    /**
     * @return For generic types, this is the generic type parameter
     */
    public Optional<Type> parameterizedType() {
        return Optional.ofNullable(parameterizedType);
    }

    /**
     * @return The media type of the schema being described
     */
    public MediaType mediaType() {
        return mediaType;
    }

    @Override
    public String toString() {
        return "SchemaObjectCustomizerContext{" +
            "target=" + target +
            ", type=" + type +
            ", parameterizedType=" + parameterizedType +
            ", resource=" + resource +
            ", method=" + method +
            ", parameter=" + parameter +
            ", mediaType=" + mediaType +
            '}';
    }
    /** @return the declared SSE name, including an empty string for unnamed events */
    public Optional<String> eventName() { return Optional.ofNullable(eventName); }
    /** @return the Java type serialized into the SSE data string */
    public Optional<Class<?>> payloadType() { return Optional.ofNullable(payloadType); }
    /** @return the serialization media type of the SSE data string */
    public Optional<MediaType> payloadMediaType() { return Optional.ofNullable(payloadMediaType); }
}
