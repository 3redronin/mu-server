package io.muserver;

import java.net.URI;
import java.util.Optional;

/**
 * Information about a request that was rejected before it became a normal request/response
 * exchange (for example a <code>431</code> when the request headers are too large).
 * @see RequestRejectListener
 */
public interface RejectedRequest {

    /**
     * @return The HTTP status code sent to the client, for example <code>431</code> or <code>503</code>.
     */
    int status();

    /**
     * @return The plain-text message sent to the client, for example
     * <code>431 Request Header Fields Too Large</code>.
     */
    String reason();

    /**
     * The HTTP method of the rejected request, on a best-effort basis.
     * <p>This is the raw method token sent by the client (such as <code>GET</code> or <code>POST</code>),
     * rather than a {@link Method} enum value, so that an unrecognised method that caused the rejection is
     * preserved as-is.</p>
     * <p>It is empty when the request was rejected before the method could be decoded, for example a
     * <code>431</code> over HTTP/2 where the header block is rejected during HPACK decoding.</p>
     *
     * @return The request method, or an empty optional if it was not decoded.
     */
    Optional<String> method();

    /**
     * The request target of the rejected request, on a best-effort basis.
     * <p>It is empty when the request was rejected before the target could be decoded (for example a
     * <code>431</code> over HTTP/2), or when the target could not be parsed as a URI. For a <code>414</code>
     * the value may be a truncated or placeholder target.</p>
     *
     * @return The request target, or an empty optional if it was not decoded or could not be parsed.
     */
    Optional<URI> uri();

    /**
     * @return The connection the rejected request arrived on, which can be used to find details such as
     * the {@link HttpConnection#remoteAddress()} and {@link HttpConnection#protocol()}.
     */
    HttpConnection connection();

}
