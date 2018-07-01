package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <p>An HTTP request from a client.</p>
 * <p>Call {@link #method()} and {@link #uri()} to find the method and URL of this request.</p>
 * <p>To read query string parameters, see {@link #query()}.</p>
 * <p>Request headers can be accessed by the {@link #headers()} method.</p>
 * <p>You can read the body as an input stream with {@link #inputStream()} or if you expect a string call {@link #readBodyAsString()}.
 * If form values were posted as the request, see {@link #form()}</p>
 * <p>Getting the value of a cookie by name is done by calling {@link #cookie(String)}</p>
 * <p>If you need to share request-specific data between handlers, use {@link #state(Object)} to set and {@link #state()} to get.</p>
 */
public interface MuRequest {

    /**
     * <p>Gets the content type of the request body, for example <code>application/json</code> or <code>null</code>
     * if there is no body.</p>
     * <p>Note: If the Content-Type header included a charset, then this will NOT be returned by this method.</p>
     * @return The content type of the request body (specified by the <code>Content-Type</code> request header),
     * or <code>null</code> if there is no body.
     */
    String contentType();

    /**
     * @return The request method, e.g. GET or POST
     */
    Method method();

    /**
     * The URI of the request at the origin.
     * <p>
     * If behind a reverse proxy, this URI should be the URI that the client saw when making the request.
     *
     * @return The full request URI
     */
    URI uri();

    /**
     * The URI of the request for this server.
     * <p>
     * If behind a reverse proxy, this will be different from {@link #uri()} as it is the actual server URI rather
     * than what the client sees.
     *
     * @return The full request URI
     */
    URI serverURI();

    /**
     * @return The request headers
     */
    Headers headers();

    /**
     * The input stream of the request, if there was a request body.
     * <p>
     * Note: this can only be read once and cannot be used with {@link #readBodyAsString()} or {@link #form()}.
     *
     * @return {@link Optional#empty()} if there is no request body; otherwise the input stream of the request body.
     */
    Optional<InputStream> inputStream();

    /**
     * Returns the request body as a string.
     * <p>
     * This is a blocking call which waits until the whole request is available. If you need the raw bytes, or to stream
     * the request body, then use the {@link #inputStream()} instead.
     * <p>
     * The content type of the request body is assumed to be UTF-8.
     * <p>
     * Note: this can only be read once and cannot be used with {@link #inputStream()} ()} or {@link #form()}.
     *
     * @return The content of the request body, or an empty string if there is no request body
     * @throws IOException if there is an exception during reading the request, e.g. if the HTTP connection is stopped during a request
     */
    String readBodyAsString() throws IOException;

    /**
     * Gets all the uploaded files with the given name, or an empty list if none are found.
     *
     * @param name The file input name to get
     * @return All the files with the given name
     * @throws IOException Thrown when there is an error while reading the file, e.g. if a user closes their
     *                     browser before the upload is complete.
     */
    List<UploadedFile> uploadedFiles(String name) throws IOException;

    /**
     * <p>Gets the uploaded file with the given name, or null if there is no upload with that name.</p>
     * <p>If there are multiple files with the same name, the first one is returned.</p>
     *
     * @param name The querystring parameter name to get
     * @return The querystring value, or an empty string
     * @throws IOException Thrown when there is an error while reading the file, e.g. if a user closes their
     *                     browser before the upload is complete.
     */
    UploadedFile uploadedFile(String name) throws IOException;

    /**
     * <p>Gets the querystring parameters for this request.</p>
     * @return Returns an object allowing you to access the querystring parameters of this request.
     */
    RequestParameters query();

    /**
     * <p>Gets the form parameters for this request.</p>
     * <p>Note: this cannot be called after a call to {@link #inputStream()} or {@link #readBodyAsString()}</p>
     * @throws IOException Thrown when there is an error while reading the form, e.g. if a user closes their
     *                     browser before the form is fully read into memory.
     * @return Returns an object allowing you to access the form parameters of this request.
     */
    RequestParameters form() throws IOException;

    /**
     * Deprecated since 1.8
     *
     * @param name The querystring parameter name to get
     * @return The querystring value, or an empty string
     * @deprecated Use <code>query().get(name)</code> instead
     */
    @Deprecated
    String parameter(String name);

    /**
     * Deprecated since 1.8
     *
     * @param name The querystring parameter name to get
     * @return All values of the parameter with the given name
     * @deprecated Use <code>query().getAll(name)</code> instead
     */
    @Deprecated
    List<String> parameters(String name);

    /**
     * Deprecated since 1.8
     *
     * @param name The name of the form element to get
     * @return The value of the form element with the given name, or an empty string
     * @throws IOException Thrown when there is an error while reading the form, e.g. if a user closes their
     *                     browser before the form is fully read into memory.
     * @deprecated Use <code>form().get(name)</code> instead
     */
    @Deprecated
    String formValue(String name) throws IOException;

    /**
     * Deprecated since 1.8
     *
     * @param name The name of the form element to get
     * @return All values of the form element with the given name
     * @throws IOException Thrown when there is an error while reading the form, e.g. if a user closes their
     *                     browser before the form is fully read into memory.
     * @deprecated Use <code>form().getAll(name)</code> instead
     */
    @Deprecated
    List<String> formValues(String name) throws IOException;

    /**
     * Gets all the client-sent cookies
     *
     * @return A set of cookie objects
     */
    Set<Cookie> cookies();

    /**
     * Gets the value of the client-sent cookie with the given name
     *
     * @param name The name of the cookie
     * @return The cookie, or {@link Optional#empty()} if there is no cookie with that name.
     */
    Optional<String> cookie(String name);

    /**
     * <p>If this handler was added to a {@link ContextHandlerBuilder#context(String)} then this
     * will return the value of the context pre-pended with a '<code>/</code>'.</p>
     * <p>For example,
     * if this handler was added to <code>ContextHandlerBuilder.context(&quot;some context&quot;)</code>
     * this this method will return <code>/some%20context</code></p>
     *
     * @return The context of the current handler or '<code>/</code>' if there is no context.
     * @see #relativePath()
     */
    String contextPath();

    /**
     * <p>The path of this request (without query string) relative to the context. If this handler is not
     * nested within a context then it simply returns the full path of the request, otherwise it is
     * the path relative to {@link #contextPath()}.</p>
     * <p>For example, if there is a context <code>some context</code>
     * and there is a request to <code>/some%20context/some%20path</code> then this method will return
     * <code>/some%20path</code></p>
     *
     * @return The relative path of this request.
     * @see #contextPath()
     */
    String relativePath();

    /**
     * <p>Gets request-specific state that was added with {@link #state(Object)}.</p>
     * <p>An example is getting user information in a view handler that was previously set by an authentication handler.</p>
     * @return An object previously set by {@link #state(Object)}, or <code>null</code> if it was never set.
     */
    Object state();

    /**
     * <p>Sets the given object as state that is bound to this request which any subsequent handlers can access.</p>
     * <p>An example use case is if you have a authentication handler that uses this method to set user information on
     * the request, and then a subsequent handler calls {@link #state()} to get the user info.</p>
     * @param value Any object to store as state.
     */
    void state(Object value);

    /**
     * <p>Specifies that you want to handle this response asynchronously.</p>
     * <p>When finished, call {@link AsyncHandle#complete()}</p>
     * @return AsyncHandle An object that you can use to mark the response as complete.
     */
    AsyncHandle handleAsync();

    /**
     * Gets the address that the request came from. Warning: this may not be the client's address and instead
     * may be an intermediary such as a network gateway.
     * @return The IP address of the client, or of a gateway with NAT, etc.
     */
    String remoteAddress();

    /**
     * @return Returns a reference to the mu server instance.
     */
    MuServer server();
}
