package io.muserver.rest;

import jakarta.ws.rs.WebApplicationException;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An RFC 9457 problem-details exception.
 */
public class ProblemDetailsException extends WebApplicationException {
    private final int status;
    private final String title;
    private final String detail;
    private final URI type;
    private final URI instance;
    private final Map<String, Object> extensionMembers;

    ProblemDetailsException(int status, String title, String detail, URI type, URI instance, Map<String, Object> extensionMembers, Throwable cause) {
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
    public String getDetail() {
        return detail;
    }

    /**
     * @return The problem type URI.
     */
    public URI getType() {
        return type;
    }

    /**
     * @return The problem instance URI.
     */
    public URI getInstance() {
        return instance;
    }

    /**
     * @return Any RFC 9457 extension members.
     */
    public Map<String, Object> getExtensionMembers() {
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
