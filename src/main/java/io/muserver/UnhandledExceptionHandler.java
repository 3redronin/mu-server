package io.muserver;

import jakarta.ws.rs.WebApplicationException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * A handler for exceptions that have been thrown by other handlers which allows for custom error pages.
 * <p>This is registered with {@link MuServerBuilder#withExceptionHandler(UnhandledExceptionHandler)}.</p>
 * <p>Note: redirect exceptions and exceptions after the response has already started will not get routed to this handler.</p>
 */
public interface UnhandledExceptionHandler {

    /**
     * Called when an exception is thrown by another handler.
     * <p>Note that if the response has already started sending data, you will not be able to add a custom error
     * message. In this case, you may want to allow for the default error handling by returning <code>false</code>.</p>
     * <p>The following shows a pattern to filter out certain errors:</p>
     * <pre><code>
     * muServerBuilder.withExceptionHandler((request, response, exception) -&gt; {
     *     if (exception instanceof NotAuthorizedException) return false;
     *     response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
     *     response.write("Oh I'm worry, there was a problem");
     *     return true;
     * })
     * </code></pre>
     * @param request The request
     * @param response The response
     * @param cause The exception thrown by an earlier handler
     * @return <code>true</code> if this handler has written a response; otherwise <code>false</code> in which case
     * the default error handler will be invoked.
     * @throws Exception Throwing an exception will result in a <code>500</code> error code being returned with a basic error message.
     */
    boolean handle(MuRequest request, MuResponse response, Throwable cause) throws Exception;

    /**
     * To be deleted
     * @param custom a custom handler
     * @return the default handler
     */
    static UnhandledExceptionHandler getDefault(UnhandledExceptionHandler custom) {
        return new BuiltInExceptionHandler(custom);
    }
}

class BuiltInExceptionHandler implements UnhandledExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(BuiltInExceptionHandler.class);

    private final UnhandledExceptionHandler customHandler;

    BuiltInExceptionHandler(UnhandledExceptionHandler customHandler) {
        this.customHandler = customHandler;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response, Throwable cause) throws Exception {
        handleIt(request, response, cause, customHandler);
        return true;
    }

    private void handleIt(MuRequest request, MuResponse response, Throwable cause, @Nullable UnhandledExceptionHandler customHandler) {
        HttpException httpException;
        if (cause instanceof HttpException) {
            httpException = (HttpException) cause;
        } else if (cause instanceof WebApplicationException) {
            httpException = convertToMu((WebApplicationException) cause);
        } else httpException = null;

        if (httpException != null && httpException.status().isRedirection()) {
            // do not render any errors - just send a redirect
            response.status(httpException.status());
            response.headers().set(httpException.responseHeaders());
            response.headers().set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
            var originalLocation = response.headers().get(HeaderNames.LOCATION);
            if (Mutils.nullOrEmpty(originalLocation)) {
                throw new IllegalStateException("Redirect exception has no location", httpException);
            }
            try {
                var normalisedLocation = new URI(originalLocation).normalize();
                if (normalisedLocation.getHost() == null) {
                    normalisedLocation = request.uri().resolve(normalisedLocation);
                }
                var uriString = normalisedLocation.toString();
                if (!Objects.equals(originalLocation, uriString)) {
                    response.headers().set(HeaderNames.LOCATION, uriString);
                }
            } catch (URISyntaxException uriE) {
                throw new IllegalStateException("Invalid redirect URI " + originalLocation, uriE);
            }
            if (httpException.getMessage() != null) {
                response.write(httpException.getMessage());
            }
        } else {
            if (customHandler == null) {
                // Use the default error response which is some basic HTML
                String body;
                if (httpException == null) {
                    // This is an unhandled exception
                    response.status(HttpStatus.INTERNAL_SERVER_ERROR_500);
                    response.headers().clear();
                    String errorID = "ERR-" + UUID.randomUUID();
                    log.info("Sending a 500 to the client with ErrorID={} for {}", errorID, request, cause);
                    body = "Oops! An unexpected error occurred. The ErrorID=" + errorID;
                } else {
                    // An HTTP  exception thrown by the user
                    response.status(httpException.status());
                    response.headers().set(httpException.responseHeaders());
                    body = httpException.getMessage();
                }
                HttpStatus newStatus = response.status();
                String encodedTitle = Mutils.htmlEncode(newStatus.toString());
                response.headers().set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
                if (newStatus.canHaveContent()) {
                    response.contentType(ContentTypes.TEXT_HTML_UTF8);
                    var bodyEl = body == null ? "" : "<p>" + Mutils.htmlEncode(body) + "</p>";
                    response.write("<html><head><title>" + encodedTitle + "</title></head><body><h1>" + encodedTitle + "</h1>" + bodyEl + "</body></html>");
                }
            } else {
                // There is a custom error renderer
                boolean handled;
                try {
                    // ask the custom handler to handle it - if it returns false we need to handle it
                    handled = customHandler.handle(request, response, cause);
                } catch (Exception exceptionFromCustomHandler) {
                    // the contract for custom handlers is that they can throw their own HTTP exceptions and have the default
                    // handle it. So if that happened here then call back to ourselves, this time with the exception from the
                    // custom handler, and with a null handler to force the default handler to render the new exception.
                    handleIt(request, response, exceptionFromCustomHandler, null);
                    handled = true;
                }
                if (!handled) {
                    // The custom handler didn't handle it, so call back on ourselves with a null custom handler to force
                    // the default HTML rendering
                    handleIt(request, response, cause, null);
                }
            }
        }
    }

    private static HttpException convertToMu(WebApplicationException cause) {
        HttpStatus status = HttpStatus.of(cause.getResponse().getStatus());
        String message = cause.getMessage();
        if (status == HttpStatus.NOT_FOUND_404 && "HTTP 404 Not Found".equals(message)) {
            message = "This page is not available. Sorry about that.";
        }
        HttpException httpException = new HttpException(status, message, cause.getCause());
        for (Map.Entry<String, List<Object>> exHeader : cause.getResponse().getHeaders().entrySet()) {
            httpException.responseHeaders().add(exHeader.getKey(), exHeader.getValue());
        }

        return httpException;
    }

}

