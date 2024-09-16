package io.muserver;

public class HttpException extends RuntimeException {
    private final HttpStatusCode status;
    private final Mu3Headers headers = Mu3Headers.newWithDate();

    public HttpException(HttpStatusCode status) {
        super(status.toString());
        this.status = status;
    }

    public HttpException(HttpStatusCode status, Throwable cause) {
        super(status.toString(), cause);
        this.status = status;
    }

    public HttpException(HttpStatusCode status, String message) {
        super(status.toString() + " - " + message);
        this.status = status;
    }

    public HttpException(HttpStatusCode status, String message, Throwable cause) {
        super(status.toString() + " - " + message, cause);
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }

    /**
     * Response headers that will be sent to the client with this HTTP exception
     * @return headers which can be modified
     */
    public Headers responseHeaders() {
        return headers;
    }
}
