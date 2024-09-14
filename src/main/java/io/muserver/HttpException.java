package io.muserver;

public class HttpException extends RuntimeException {
    private final HttpStatusCode status;

    public HttpException(HttpStatusCode status) {
        super(status.toString());
        this.status = status;
    }

    public HttpException(HttpStatusCode status, Throwable cause) {
        super(status.toString(), cause);
        this.status = status;
    }

    public HttpException(HttpStatusCode status, String message) {
        super(status.toString());
        this.status = status;
    }

    public HttpException(HttpStatusCode status, String message, Throwable cause) {
        super(status.toString(), cause);
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
