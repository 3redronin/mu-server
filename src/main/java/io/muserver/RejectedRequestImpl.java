package io.muserver;

class RejectedRequestImpl implements RejectedRequest {

    private final int status;
    private final String reason;
    private final HttpConnection connection;

    RejectedRequestImpl(int status, String reason, HttpConnection connection) {
        this.status = status;
        this.reason = reason;
        this.connection = connection;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public HttpConnection connection() {
        return connection;
    }

    @Override
    public String toString() {
        return "RejectedRequest{" +
            "status=" + status +
            ", reason='" + reason + '\'' +
            ", connection=" + connection +
            '}';
    }
}
