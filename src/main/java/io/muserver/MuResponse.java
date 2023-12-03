package io.muserver;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;

/**
 * <p>A response sent to a client.</p>
 * <p>The {@link #status(int)} and {@link #headers()} methods are used to set the response code and response headers.</p>
 * <p>The {@link #addCookie(Cookie)} method can be used to add a cookie to the response.</p>
 * <p>There are several ways to send data back to the client:</p>
 * <ul>
 *     <li>{@link #write(String)} to send a text response without chunking.</li>
 *     <li>{@link #sendChunk(String)} to send a chunk of text (unlike <code>write</code> it can be called multiple times)</li>
 *     <li>{@link #outputStream()} to send bytes</li>
 *     <li>{@link #writer()} to send text as an output stream.</li>
 * </ul>
 * <p><strong>Note:</strong> only one of the above methods can be used per response, and aside from <code>sendChunk</code>
 * it is not allowed to call the same method more than once..</p>
 */
public interface MuResponse {

    /**
     * @return The HTTP status of this request.
     */
    int status();

    /**
     * Sets the response code for this request. Defaults to <code>200</code>
     * @param value The response code to send to the client.
     */
    void status(int value);

    /**
     * <p>Writes the given text as the response body for this request. This can only be called once.</p>
     * <p>If you want to send multiple chunks of text, see {@link #sendChunk(String)}</p>
     * @param text The full response body to send to the client.
     * @throws IllegalStateException Thrown if this is called twice, or this is called after any other body-writing methods.
     */
    void write(String text);

    /**
     * Immediately sends the given text to the client as a chunk.
     * @param text Text to send to the client as an HTTP chunk.
     * @throws IllegalStateException Thrown if {@link #write(String)} or {@link #outputStream()} or {@link #writer()} was
     * already called.
     */
    void sendChunk(String text);

    /**
     * <p>Redirects to the given URL. If relative, it will be converted to an absolute URL.</p>
     * <p>The response code will be 302 unless the status is already set to 300, 301, or 303.</p>
     * @param url The full or relative URL to redirect to.
     */
    void redirect(String url);

    /**
     * <p>Redirects to the given URL. If relative, it will be converted to an absolute URL.</p>
     * <p>The response code will be 302 unless the status is already set to 300, 301, or 303.</p>
     * @param uri The full or relative URI to redirect to.
     */
    void redirect(URI uri);

    /**
     * Gets the response headers map which can be used to specify response headers. Example:
     * <code>response.headers().set("access-control-allow-origin", "*");</code>
     * @return The response headers map that can be used to set headers.
     */
    Headers headers();

    /**
     * Sets the Content-Type response header.
     * @see ContentTypes
     * @param contentType The content type of the response, for example <code>application/json</code>
     */
    void contentType(CharSequence contentType);

    /**
     * <p>Sends a cookie to the client.</p>
     * <p>Example: <code>response.addCookie(new Cookie("user", user));</code></p>
     * <p>If using HTTPS, it's recommended to use <code>response.addCookie(Cookie.secureCookie("user", user));</code></p>
     * @param cookie A cookie to store on the client.
     */
    void addCookie(io.muserver.Cookie cookie);

    /**
     * <p>Gets a buffered output stream that can send data to the client.</p>
     * <p>To set the size of the buffer, use @{link {@link #outputStream(int)}} instead.</p>
     * <p>If you are writing text, you may prefer the {@link #writer()} or {@link #sendChunk(String)} methods.</p>
     * @return An output stream to send data to the client.
     */
    OutputStream outputStream();

    /**
     * <p>Gets a buffered output stream that can send data to the client.</p>
     * <p>If you are writing text, you may prefer the {@link #writer()} or {@link #sendChunk(String)} methods.</p>
     * @param bufferSize The size of the output buffer, e.g. 8192, or 0 if you do not want to buffer the response bytes.
     * @return An output stream to send data to the client.
     */
    OutputStream outputStream(int bufferSize);

    /**
     * <p>A print writer that can be used to send text to the client. It is a convenience method, wrapping {@link #outputStream()}
     * in a PrintWriter.</p>
     * <p>You may prefer using {@link #sendChunk(String)} or {@link #write(String)} to send text.</p>
     * @return A print writer that can be used to send text to the client.
     */
    PrintWriter writer();

    /**
     * Specifies whether or not any response data has already been sent to the client. Note that once any data is sent to
     * the client then {@link #status(int)} and {@link #headers()} can no longer be changed.
     * @return Returns <code>true</code> if any data has been sent to the client; otherwise <code>false</code>.
     */
    boolean hasStartedSendingData();

    /**
     * @return The current state of this response
     */
    ResponseState responseState();
}