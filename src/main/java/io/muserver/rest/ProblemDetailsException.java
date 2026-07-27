package io.muserver.rest;

import jakarta.ws.rs.WebApplicationException;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An RFC 9457 problem-details exception.
 *
 * @serial exclude
 */
public class ProblemDetailsException extends WebApplicationException {
    private final int status;
    private final String title;
    private final @Nullable String detail;
    private final @Nullable URI type;
    private final URI instance;
    private final Map<String, @Nullable Object> extensionMembers;

    ProblemDetailsException(int status, String title, @Nullable String detail, @Nullable URI type, URI instance,
                            Map<String, @Nullable Object> extensionMembers, @Nullable Throwable cause) {
        super(detail != null ? detail : title, cause, status);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.type = type;
        this.instance = instance;
        this.extensionMembers = Collections.unmodifiableMap(new LinkedHashMap<>(extensionMembers));
    }

    /**
     * Gets the HTTP status code for the problem response.
     *
     * @return The HTTP status code for the problem response.
     */
    public int getStatus() {
        return status;
    }

    /**
     * Gets the short, human-readable title for the problem response.
     *
     * @return The short title for the problem response.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Gets the human-readable detail specific to this occurrence of the problem.
     *
     * @return The human-readable detail for the problem response, or {@code null}.
     */
    public @Nullable String getDetail() {
        return detail;
    }

    /**
     * Gets the URI identifying the problem type.
     *
     * @return The problem type URI.
     */
    public @Nullable URI getType() {
        return type;
    }

    /**
     * Gets the URI identifying this specific occurrence of the problem.
     *
     * @return The problem instance URI.
     */
    public URI getInstance() {
        return instance;
    }

    /**
     * Gets the RFC 9457 extension members that will be included in the response body.
     *
     * @return Any RFC 9457 extension members.
     */
    public Map<String, @Nullable Object> getExtensionMembers() {
        return extensionMembers;
    }

    /**
     * Creates a builder for a problem-details exception.
     *
     * @return a builder to create a new ProblemDetailsException
     */
    public static ProblemDetailsExceptionBuilder builder() {
        return new ProblemDetailsExceptionBuilder();
    }

    /**
     * Creates a builder for a problem-details exception with a preset status.
     *
     * @param status the HTTP status code for the problem response
     * @return a builder to create a new ProblemDetailsException with the given status
     */
    public static ProblemDetailsExceptionBuilder builder(int status) {
        return ProblemDetailsExceptionBuilder.problemDetailsException(status);
    }
}
