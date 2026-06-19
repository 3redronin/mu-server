package io.muserver.rest;

import java.net.URI;
import java.util.*;

/**
 * Builds {@link ProblemDetailsException} instances that can be serialized by
 * {@link ProblemDetailsExceptionMapper} as RFC 9457 problem-details JSON.
 * <p>
 * Throw this exception from JAX-RS resource methods handled by mu-server REST. For it to affect HTTP responses, the
 * mapper must be registered on the REST handler via
 * {@link ProblemDetailsExceptionMapperBuilder#problemDetailsExceptionMapper()}; otherwise it is treated like a normal
 * unhandled exception.
 * </p>
 */
public class ProblemDetailsExceptionBuilder {
    private static final String ABOUT_BLANK = "about:blank";

    private int status = 500;
    private String title;
    private String detail;
    private URI type;
    private URI instance;
    private Throwable cause;
    private final Map<String, Object> extensionMembers = new LinkedHashMap<>();

    /**
     * Creates a builder with the default 500 status.
     *
     * @return A new builder.
     */
    public static ProblemDetailsExceptionBuilder problemDetailsException() {
        return new ProblemDetailsExceptionBuilder();
    }

    /**
     * Creates a builder with the given status code.
     *
     * @param status The HTTP status code.
     * @return A new builder.
     */
    public static ProblemDetailsExceptionBuilder problemDetailsException(int status) {
        return new ProblemDetailsExceptionBuilder().withStatus(status);
    }

    /**
     * Sets the HTTP status code of the problem response.
     * <p>
     * Use the status your endpoint would normally return for the error (for example 400, 404, 409, 422, 500).
     * </p>
     *
     * @param status The HTTP status code.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withStatus(int status) {
        this.status = status;
        return this;
    }

    /**
     * Sets the short human-readable summary of the problem.
     * <p>
     * This is a concise, stable headline for this problem category, such as {@code "Validation Failed"}.
     * When omitted, a default title based on the HTTP status code is used.
     * </p>
     *
     * @param title The title.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * Sets the human-readable detail explaining this specific occurrence.
     * <p>
     * Use this for occurrence-specific information that helps clients or operators understand this exact failure.
     * </p>
     *
     * @param detail The detail.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withDetail(String detail) {
        this.detail = detail;
        return this;
    }

    /**
     * Sets the underlying cause of the problem details exception.
     * <p>
     * When the problem-details mapper logs this exception, the cause will be included in the log output, which may help
     * with problem diagnosis.
     * </p>
     *
     * @param cause The cause.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withCause(Throwable cause) {
        this.cause = cause;
        return this;
    }

    /**
     * Sets the problem {@code type} URI that identifies the general problem category.
     * <p>
     * Provide your own type when you want a stable identifier that documentation and clients can key off, for
     * example {@code https://example.com/problems/validation}. A URI is used because it is globally unique and can
     * optionally link to human-readable documentation.
     * </p>
     * <p>
     * If not set, it defaults to {@code about:blank}, which RFC 9457 defines for generic problems where no
     * application-specific type is needed.
     * </p>
     *
     * @param type The type URI.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withType(URI type) {
        this.type = type;
        return this;
    }

    /**
     * Sets the problem {@code instance} URI for this specific occurrence.
     * <p>
     * {@code instance} is useful as a correlation ID: clients can report it and operators can find the exact
     * corresponding logs or traces. If not set, a random {@code urn:uuid:...} value is generated automatically.
     * </p>
     * <p>
     * See {@link ProblemDetailsExceptionMapperBuilder#withLog4xxProblemDetailsInstanceIds(boolean)} and
     * {@link ProblemDetailsExceptionMapperBuilder#withLog5xxProblemDetailsInstanceIds(boolean)} to control whether
     * generated instance IDs are logged by the mapper.
     * </p>
     *
     * @param instance The instance URI.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withInstance(URI instance) {
        this.instance = instance;
        return this;
    }

    /**
     * Replaces all extension members (custom RFC 9457 fields beyond the standard ones).
     * <p>
     * Use extension members for API-specific machine-readable metadata such as validation codes, field names,
     * or domain-specific error identifiers.
     * </p>
     *
     * @param extensionMembers The extension members to use.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder withExtensionMembers(Map<String, Object> extensionMembers) {
        Objects.requireNonNull(extensionMembers, "extensionMembers");
        this.extensionMembers.clear();
        for (Map.Entry<String, Object> entry : extensionMembers.entrySet()) {
            addExtensionMember(entry.getKey(), entry.getValue());
        }
        return this;
    }

    /**
     * Adds a single extension member (custom RFC 9457 field).
     * <p>
     * Extension names must not be one of the reserved standard problem fields:
     * {@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}.
     * </p>
     *
     * @param name The extension member name.
     * @param value The extension member value.
     * @return This builder.
     */
    public ProblemDetailsExceptionBuilder addExtensionMember(String name, Object value) {
        validateExtensionName(name);
        extensionMembers.put(name, value);
        return this;
    }

    /**
     * @return The HTTP status code currently configured for the problem.
     */
    public int status() {
        return status;
    }

    /**
     * @return The title currently configured for the problem, or {@code null}.
     */
    public String title() {
        return title;
    }

    /**
     * @return The detail currently configured for the problem, or {@code null}.
     */
    public String detail() {
        return detail;
    }

    /**
     * @return The cause currently configured for the problem, or {@code null}.
     */
    public Throwable cause() {
        return cause;
    }

    /**
     * @return The type URI currently configured for the problem, or {@code null}.
     */
    public URI type() {
        return type;
    }

    /**
     * @return The instance URI currently configured for the problem, or {@code null}.
     */
    public URI instance() {
        return instance;
    }

    /**
     * @return The extension members currently configured for the problem.
     */
    public Map<String, Object> extensionMembers() {
        return Collections.unmodifiableMap(extensionMembers);
    }

    /**
     * Creates the exception.
     *
     * @return A new problem-details exception.
     */
    public ProblemDetailsException build() {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status code");
        }
        String effectiveTitle = title == null ? defaultTitle(status) : title;
        URI effectiveInstance = instance == null ? URI.create("urn:uuid:" + UUID.randomUUID()) : instance;
        return new ProblemDetailsException(status, effectiveTitle, detail, type, effectiveInstance, extensionMembers, cause);
    }

    private static String defaultTitle(int status) {
        jakarta.ws.rs.core.Response.Status statusInfo = jakarta.ws.rs.core.Response.Status.fromStatusCode(status);
        if (statusInfo != null) {
            return statusInfo.getReasonPhrase();
        }
        switch (jakarta.ws.rs.core.Response.Status.Family.familyOf(status)) {
            case CLIENT_ERROR:
                return "Client Error";
            case SERVER_ERROR:
                return "Internal Server Error";
            default:
                return "HTTP " + status;
        }
    }

    private static void validateExtensionName(String name) {
        Objects.requireNonNull(name, "name");
        if ("type".equals(name) || "title".equals(name) || "status".equals(name) || "detail".equals(name) || "instance".equals(name)) {
            throw new IllegalArgumentException("Extension member names must not use reserved problem-details fields: " + name);
        }
    }
}
