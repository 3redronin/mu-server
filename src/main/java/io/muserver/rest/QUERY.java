package io.muserver.rest;

import jakarta.ws.rs.HttpMethod;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Handles a safe, idempotent HTTP QUERY request, as defined by RFC 10008.
 * A valid Content-Type is required, including for an empty request body.
 * Use {@link jakarta.ws.rs.Consumes} to declare supported query formats and
 * {@link jakarta.ws.rs.Produces} to declare result formats.
 * Automatic OPTIONS and unsupported-format responses advertise supported formats in Accept-Query.
 * Query evaluation, caching and stored results remain application responsibilities.
 * <pre>
 * &#64;QUERY
 * &#64;Consumes("text/plain")
 * &#64;Produces("text/plain")
 * public String search(String query) {
 *     return "Result for " + query;
 * }
 * </pre>
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
@HttpMethod("QUERY")
public @interface QUERY {
}
