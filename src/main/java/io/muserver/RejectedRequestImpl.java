package io.muserver;

import java.net.URI;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

class RejectedRequestImpl implements RejectedRequest {

    private final int status;
    private final String reason;
    private final String method;
    private final @Nullable URI uri;
    private final HttpConnection connection;

    RejectedRequestImpl(int status, String reason, @Nullable String method, @Nullable String uri, HttpConnection connection) {
        this.status = status;
        this.reason = reason;
        this.method = method;
        this.uri = parseUriOrNull(uri);
        this.connection = connection;
    }

    private static @Nullable URI parseUriOrNull(@Nullable String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()) {
            return null;
        }
        try {
            return URI.create(rawTarget);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
    public Optional<String> method() {
        return Optional.ofNullable(method);
    }

    @Override
    public Optional<URI> uri() {
        return Optional.ofNullable(uri);
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
            ", method='" + method + '\'' +
            ", uri=" + uri +
            ", connection=" + connection +
            '}';
    }
}
