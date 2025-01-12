package io.muserver;

import org.jspecify.annotations.Nullable;

import java.net.URI;

public class HttpException extends RuntimeException {
    private final HttpStatus status;
    private final Mu3Headers headers = Mu3Headers.newWithDate();

    public HttpException(HttpStatus status) {
        super(status.code() == 404 ? "This page is not available. Sorry about that." : status.toString());
        this.status = status;
    }

    public HttpException(HttpStatus status, Throwable cause) {
        super(status.toString(), cause);
        this.status = status;
    }

    public HttpException(HttpStatus status, @Nullable String message) {
        super(message);
        this.status = status;
    }

    public HttpException(HttpStatus status, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.status = status;
    }

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

    public static HttpException redirect(URI location) {
        var ex = new HttpException(HttpStatus.FOUND_302, (String)null);
        ex.headers.set(HeaderNames.LOCATION, location.toString());
        return ex;
    }
    public static HttpException notFound() {
        return new HttpException(HttpStatus.NOT_FOUND_404);
    }
    public static HttpException notFound(String message) {
        return new HttpException(HttpStatus.NOT_FOUND_404, message);
    }

    public static HttpException badRequest() {
        return new HttpException(HttpStatus.BAD_REQUEST_400);
    }
    public static HttpException badRequest(String message) {
        return new HttpException(HttpStatus.BAD_REQUEST_400, message);
    }
    public static HttpException internalServerError() {
        return new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500);
    }
    public static HttpException internalServerError(String message) {
        return new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, message);
    }

    static HttpException requestTimeout() {
        var e = new HttpException(HttpStatus.REQUEST_TIMEOUT_408);
        e.headers.set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        return e;
    }

}
