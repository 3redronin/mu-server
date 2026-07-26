package io.muserver;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * A runtime exception that carries an HTTP status and optional response headers.
 *
 * <p>Throw this from a handler when you want mu-server to terminate normal processing and send a specific
 * HTTP error or redirect response to the client.</p>
 */
public class HttpException extends RuntimeException {
    /**
     * The HTTP status to send to the client.
     */
    private final HttpStatus status;
    /**
     * Mutable response headers that will be sent with the generated response.
     */
    private final FieldBlock headers = FieldBlock.newWithDate();

    /**
     * Creates an HTTP exception with the given status and a default message derived from that status.
     *
     * @param status The HTTP status to send.
     */
    public HttpException(HttpStatus status) {
        super(status.code() == 404 ? "This page is not available. Sorry about that." : status.toString());
        this.status = status;
    }

    /**
     * Creates an HTTP exception with the given status and cause.
     *
     * @param status The HTTP status to send.
     * @param cause The underlying cause of the failure.
     */
    public HttpException(HttpStatus status, Throwable cause) {
        super(status.toString(), cause);
        this.status = status;
    }

    /**
     * Creates an HTTP exception with the given status and message.
     *
     * @param status The HTTP status to send.
     * @param message The message associated with the exception.
     */
    public HttpException(HttpStatus status, @Nullable String message) {
        super(message);
        this.status = status;
    }

    /**
     * Creates an HTTP exception with the given status, message, and cause.
     *
     * @param status The HTTP status to send.
     * @param message The message associated with the exception.
     * @param cause The underlying cause of the failure.
     */
    public HttpException(HttpStatus status, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * Gets the HTTP status associated with this exception.
     *
     * @return The HTTP status that mu-server will send to the client.
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Response headers that will be sent to the client with this HTTP exception
     * @return headers which can be modified
     */
    public Headers responseHeaders() {
        return headers;
    }

    /**
     * Creates a redirect exception that sends a <code>302 Found</code> response with a <code>Location</code> header.
     *
     * @param location The URI to redirect the client to.
     * @return A redirect HTTP exception.
     */
    public static HttpException redirect(URI location) {
        var ex = new HttpException(HttpStatus.FOUND_302, (String)null);
        ex.headers.set(HeaderNames.LOCATION, location.toString());
        return ex;
    }

    /**
     * Creates a <code>404 Not Found</code> exception with the default message.
     *
     * @return A not-found HTTP exception.
     */
    public static HttpException notFound() {
        return new HttpException(HttpStatus.NOT_FOUND_404);
    }

    /**
     * Creates a <code>404 Not Found</code> exception with a custom message.
     *
     * @param message The message associated with the exception.
     * @return A not-found HTTP exception.
     */
    public static HttpException notFound(String message) {
        return new HttpException(HttpStatus.NOT_FOUND_404, message);
    }

    /**
     * Creates a <code>400 Bad Request</code> exception with the default message.
     *
     * @return A bad-request HTTP exception.
     */
    public static HttpException badRequest() {
        return new HttpException(HttpStatus.BAD_REQUEST_400);
    }

    /**
     * Creates a <code>400 Bad Request</code> exception with a custom message.
     *
     * @param message The message associated with the exception.
     * @return A bad-request HTTP exception.
     */
    public static HttpException badRequest(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST_400, message);
    }

    /**
     * Creates a <code>500 Internal Server Error</code> exception with the default message.
     *
     * @return An internal-server-error HTTP exception.
     */
    public static HttpException internalServerError() {
        return new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500);
    }

    /**
     * Creates a <code>500 Internal Server Error</code> exception with a custom message.
     *
     * @param message The message associated with the exception.
     * @return An internal-server-error HTTP exception.
     */
    public static HttpException internalServerError(String message) {
        return new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, message);
    }

    static HttpException requestTimeout() {
        var e = new HttpException(HttpStatus.REQUEST_TIMEOUT_408);
        e.headers.set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        return e;
    }

}
