package io.muserver.rest;

import jakarta.ws.rs.WebApplicationException;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An RFC 9457 problem-details exception.
 */
public class ProblemDetailsException extends WebApplicationException {
    private final int status;
    private final String title;
    private final @Nullable String detail;
    private final @Nullable URI type;
    private final @Nullable URI instance;
    private final Map<String, @Nullable Object> extensionMembers;

    ProblemDetailsException(int status, String title, @Nullable String detail, @Nullable URI type, @Nullable URI instance, Map<String, @Nullable Object> extensionMembers, Throwable cause) {
        super(detail != null ? detail : title, cause, status);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.type = type;
        this.instance = instance;
        this.extensionMembers = Collections.unmodifiableMap(new LinkedHashMap<>(extensionMembers));
    }

    /**
     * @return The HTTP status code for the problem response.
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return The short title for the problem response.
     */
    public String getTitle() {
        return title;
    }

    /**
     * @return The human-readable detail for the problem response, or {@code null}.
     */
    public @Nullable String getDetail() {
        return detail;
    }

    /**
     * @return The problem type URI.
     */
    public @Nullable URI getType() {
        return type;
    }

    /**
     * @return The problem instance URI.
     */
    public @Nullable URI getInstance() {
        return instance;
    }

    /**
     * @return Any RFC 9457 extension members.
     */
    public Map<String, @Nullable Object> getExtensionMembers() {
        return extensionMembers;
    }

    /**
     * @return a builder to create a new ProblemDetailsException
     */
    public static ProblemDetailsExceptionBuilder builder() {
        return new ProblemDetailsExceptionBuilder();
    }
    /**
     * @param status the HTTP status code for the problem response
     * @return a builder to create a new ProblemDetailsException with the given status
     */
    public static ProblemDetailsExceptionBuilder builder(int status) {
        return ProblemDetailsExceptionBuilder.problemDetailsException(status);
    }
}
