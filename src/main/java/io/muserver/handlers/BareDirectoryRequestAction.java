package io.muserver.handlers;

/**
 * An action to take for a request to a directory where the path does not have a trailing slash.
 * <p>For example, a request to <code>/directory</code> could be seen to be invalid, or it could
 * redirect to <code>/directory/</code></p>
 */
public enum BareDirectoryRequestAction {
    /**
     * The request to <code>/{dirname}</code> should have an HTTP redirect to <code>/{dirname}/</code>
     */
    REDIRECT_WITH_TRAILING_SLASH,
    /**
     * Treat a request to a directory without a trailing space as a request to a not found resource
     */
    TREAT_AS_NOT_FOUND
}
