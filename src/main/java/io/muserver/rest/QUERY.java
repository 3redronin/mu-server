package io.muserver.rest;

import jakarta.ws.rs.HttpMethod;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Handles a safe, idempotent HTTP QUERY request, as defined by RFC 10008.
 * <p>A valid Content-Type is required, including for an empty request body.
 * Use {@link jakarta.ws.rs.Consumes} to declare supported query formats and
 * {@link jakarta.ws.rs.Produces} to declare result formats.</p>
 * <p>The Accept-Query response header tells clients which content types a resource accepts for query bodies.
 * Mu Server generates it from {@code @Consumes} on automatic OPTIONS and unsupported-format (415) responses,
 * helping clients choose a Content-Type for their next QUERY request.</p>
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
