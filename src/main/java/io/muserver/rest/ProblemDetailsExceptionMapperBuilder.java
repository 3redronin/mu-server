package io.muserver.rest;

/**
 * Builds {@link ProblemDetailsExceptionMapper} instances.
 * <p>
 * Register the built mapper on a REST handler so exceptions from JAX-RS resource methods are serialized as RFC 9457
 * problem-details JSON:
 * </p>
 * <pre>{@code
 * muServerBuilder
 *     .addHandler(restHandler(new ExampleResource())
 *     .addExceptionMapper(Throwable.class, problemDetailsExceptionMapper().build()))
 *     .start();
 * }</pre>
 * <p>
 * Example resource code using {@link ProblemDetailsExceptionBuilder}:
 * </p>
 * <pre>{@code
 * throw ProblemDetailsException.builder(422)
 *     .withTitle("Validation Failed")
 *     .withDetail("Field 'email' is required")
 *     .withType(java.net.URI.create("https://example.com/problems/validation"))
 *     .addExtensionMember("errorCode", "USR_001")
 *     .build();
 * }</pre>
 * <p>
 * Resulting response body:
 * </p>
 * <pre>{@code
 * {
 *   "type": "https://example.com/problems/validation",
 *   "title": "Validation Failed",
 *   "status": 422,
 *   "detail": "Field 'email' is required",
 *   "instance": "urn:uuid:3a95d8a5-f4ff-4d39-9f89-d462bd7e7db2",
 *   "errorCode": "USR_001"
 * }
 * }</pre>
 * <p>Note: all values are optional.</p>
 */
public class ProblemDetailsExceptionMapperBuilder {
    private boolean log4xxProblemDetailsInstanceIds = true;
    private boolean log5xxProblemDetailsInstanceIds = true;

    /**
     * Creates a builder for a {@link ProblemDetailsExceptionMapper}.
     *
     * @return A new builder.
     */
    public static ProblemDetailsExceptionMapperBuilder problemDetailsExceptionMapper() {
        return new ProblemDetailsExceptionMapperBuilder();
    }

    /**
     * Sets whether generated problem {@code instance} URIs should be logged for client errors (4xx).
     * <p>
     * This is useful when callers may report an {@code instance} URI and you want to correlate that specific failure
     * in server logs. See {@link ProblemDetailsExceptionBuilder#withInstance(java.net.URI)} for setting your own
     * instance ID.
     * </p>
     * <p>This is enabled by default.</p>
     *
     * @param log4xxProblemDetailsInstanceIds {@code true} to log generated instance URIs for 4xx responses.
     * @return This builder.
     */
    public ProblemDetailsExceptionMapperBuilder withLog4xxProblemDetailsInstanceIds(boolean log4xxProblemDetailsInstanceIds) {
        this.log4xxProblemDetailsInstanceIds = log4xxProblemDetailsInstanceIds;
        return this;
    }

    /**
     * @return Whether generated problem {@code instance} URIs will be logged for 4xx responses.
     */
    public boolean log4xxProblemDetailsInstanceIds() {
        return log4xxProblemDetailsInstanceIds;
    }

    /**
     * Sets whether generated problem {@code instance} URIs should be logged for server errors (5xx).
     * <p>
     * Keep this enabled to make troubleshooting unexpected failures easier when clients provide the {@code instance}
     * URI they received. See {@link ProblemDetailsExceptionBuilder#withInstance(java.net.URI)} for custom
     * instance IDs.
     * </p>
     * <p>This is enabled by default.</p>
     *
     * @param log5xxProblemDetailsInstanceIds {@code true} to log generated instance URIs for 5xx responses.
     * @return This builder.
     */
    public ProblemDetailsExceptionMapperBuilder withLog5xxProblemDetailsInstanceIds(boolean log5xxProblemDetailsInstanceIds) {
        this.log5xxProblemDetailsInstanceIds = log5xxProblemDetailsInstanceIds;
        return this;
    }

    /**
     * @return Whether generated problem {@code instance} URIs will be logged for 5xx responses.
     */
    public boolean log5xxProblemDetailsInstanceIds() {
        return log5xxProblemDetailsInstanceIds;
    }

    /**
     * Creates the exception mapper.
     *
     * @param <E> the exception type of the mapper
     * @return A new mapper.
     */
    public <E extends Throwable> ProblemDetailsExceptionMapper<E> build() {
        return new ProblemDetailsExceptionMapper<>(log4xxProblemDetailsInstanceIds, log5xxProblemDetailsInstanceIds);
    }
}
