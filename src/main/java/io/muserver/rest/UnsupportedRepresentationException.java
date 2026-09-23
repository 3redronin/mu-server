package io.muserver.rest;

import jakarta.ws.rs.NotSupportedException;

/** Identifies framework entity-provider failures, distinct from application responses. */
final class UnsupportedRepresentationException extends NotSupportedException {
    UnsupportedRepresentationException(String message) { super(message); }
    UnsupportedRepresentationException(String message, Throwable cause) { super(message, cause); }
}
