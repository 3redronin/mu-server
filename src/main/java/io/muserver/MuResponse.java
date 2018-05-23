package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.CharsetUtil;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

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

    Future<Void> writeAsync(String text);

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
     * Redirects to the given URL. If relative, it will be converted to an absolute URL.
     * @param url The full or relative URL to redirect to.
     */
    void redirect(String url);

    /**
     * Redirects to the given URI. If relative, it will be converted to an absolute URL.
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
     * <p>Gets an output stream that sends an HTTP chunk each time the <code>write</code>
     * method is called.</p>
     * <p>You may consider wrapping it in a {@link java.io.BufferedOutputStream} if you want to buffer the chunks before sending to the client.</p>
     * <p>If you are writing text, you may prefer the {@link #writer()} or {@link #sendChunk(String)} methods.</p>
     * @return An output stream to send data to the client.
     */
    OutputStream outputStream();

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
}

class NettyResponseAdaptor implements MuResponse {
    private final boolean isHead;
    private OutputState outputState = OutputState.NOTHING;
    private final ChannelHandlerContext ctx;
    private final NettyRequestAdapter request;
    private final Headers headers = new Headers();
    private ChannelFuture lastAction;
    private int status = 200;

    private enum OutputState {
        NOTHING, FULL_SENT, CHUNKING
    }

    public NettyResponseAdaptor(ChannelHandlerContext ctx, NettyRequestAdapter request) {
        this.ctx = ctx;
        this.request = request;
        this.isHead = request.method() == Method.HEAD;
    }

    public int status() {
        return status;
    }

    public void status(int value) {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot set the status after the headers have already been sent");
        }
        status = value;
    }

    private void startChunking() {
        if (outputState != OutputState.NOTHING) {
            throw new IllegalStateException("Cannot start chunking when state is " + outputState);
        }
        outputState = OutputState.CHUNKING;
        HttpResponse response = isHead ? new EmptyHttpResponse(httpStatus()) : new DefaultHttpResponse(HTTP_1_1, httpStatus(), false);


        writeHeaders(response, headers, request);

        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);

        lastAction = ctx.writeAndFlush(response);
    }

    private static void writeHeaders(HttpResponse response, Headers headers, NettyRequestAdapter request) {
        response.headers().add(headers.nettyHeaders());
        if (request.isKeepAliveRequested()) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
    }

    public Future<Void> writeAsync(String text) {
        sendChunk(text);
        return lastAction;
    }


    public void write(String text) {
        if (outputState != OutputState.NOTHING) {
            String what = outputState == OutputState.FULL_SENT ? "twice for one response" : "after sending chunks";
            throw new IllegalStateException("You cannot call write " + what + ". If you want to send text in multiple chunks, use sendChunk instead.");
        }
        outputState = OutputState.FULL_SENT;
        FullHttpResponse resp = isHead ?
            new EmptyHttpResponse(httpStatus())
            : new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), textToBuffer(text), false);

        writeHeaders(resp, this.headers, request);
        HttpUtil.setContentLength(resp, text.length());
        lastAction = ctx.writeAndFlush(resp);
    }

    public void sendChunk(String text) {
        if (outputState == OutputState.NOTHING) {
            startChunking();
        }
        lastAction = ctx.writeAndFlush(new DefaultHttpContent(textToBuffer(text)));
    }

    private static ByteBuf textToBuffer(String text) {
        return Unpooled.copiedBuffer(text, CharsetUtil.UTF_8);
    }

    public void redirect(String newLocation) {
        redirect(URI.create(newLocation));
    }

    public void redirect(URI newLocation) {
        URI absoluteUrl = request.uri().resolve(newLocation);
        status(302);
        headers().set(HeaderNames.LOCATION, absoluteUrl.toString());
        HttpResponse resp = new EmptyHttpResponse(httpStatus());
        writeHeaders(resp, this.headers, request);
        HttpUtil.setContentLength(resp, 0);
        lastAction = ctx.writeAndFlush(resp);
    }

    public Headers headers() {
        return headers;
    }

    public void contentType(CharSequence contentType) {
        headers.set(HeaderNames.CONTENT_TYPE, contentType);
    }

    public void addCookie(io.muserver.Cookie cookie) {
        headers.add(HeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie.nettyCookie));
    }

    public OutputStream outputStream() {
        startChunking();
        return new ChunkedHttpOutputStream(ctx);
    }

    public PrintWriter writer() {
        return new PrintWriter(new OutputStreamWriter(outputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public boolean hasStartedSendingData() {
        return outputState != OutputState.NOTHING;
    }

    public Future<Void> complete() {
        if (outputState == OutputState.NOTHING) {
            HttpResponse msg = isHead ?
                new EmptyHttpResponse(httpStatus()) :
                new DefaultFullHttpResponse(HTTP_1_1, httpStatus(), false);
            msg.headers().add(this.headers.nettyHeaders());
            if (request.method() != Method.HEAD || !(msg.headers().contains(HeaderNames.CONTENT_LENGTH))) {
                msg.headers().set(HeaderNames.CONTENT_LENGTH, 0);
            }
            lastAction = ctx.writeAndFlush(msg);
        } else if (outputState == OutputState.CHUNKING && !isHead) {
            lastAction = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
        if (!request.isKeepAliveRequested()) {
            lastAction = lastAction.addListener(ChannelFutureListener.CLOSE);
        }
        return lastAction;
    }

    private HttpResponseStatus httpStatus() {
        return HttpResponseStatus.valueOf(status());
    }

    static class EmptyHttpResponse extends DefaultFullHttpResponse {
        EmptyHttpResponse(HttpResponseStatus status) {
            super(HttpVersion.HTTP_1_1, status, false);
        }
    }

}