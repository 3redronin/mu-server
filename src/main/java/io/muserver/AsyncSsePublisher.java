package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * <p>An interface for sending Server-Sent Events (SSE) to a client with async callbacks.</p>
 * <p>The usage is that same as for the synchronous version except that each send method returns a {@link CompletionStage}
 * which contains completion or exception info.</p>
 *
 * @see SsePublisher
 * @deprecated As of Mu Server 3, the (blocking) {@link SsePublisher} is preferable in terms of performance
 */
@Deprecated
public interface AsyncSsePublisher {

    /**
     * Sends a message (without an ID or event type)
     *
     * @param message The message to send
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message);

    /**
     * <p>Sends a message with an event type (without an ID).</p>
     * <p>Clients can use the event type to listen to different types of events, for example if the event type is <code>pricechange</code>
     * the the following JavaScript can be used:</p>
     * <pre><code>
     *     var source = new EventSource('/streamer');
     *     source.addEventListener('pricechange', e =&gt; console.log(e.data));
     * </code></pre>
     *
     * @param message The message to send
     * @param event   An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message, @Nullable String event);

    /**
     * <p>Sends a message with an event type and ID.</p>
     * <p>Clients can use the event type to listen to different types of events, for example if the event type is <code>pricechange</code>
     * the the following JavaScript can be used:</p>
     * <pre><code>
     *     var source = new EventSource('/streamer');
     *     source.addEventListener('pricechange', e =&gt; console.log(e.data));
     * </code></pre>
     *
     * @param message The message to send
     * @param event   An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @param eventID An identifier for the message. If set, and the browser reconnects, then the last event ID will be
     *                sent by the browser in the <code>Last-Event-ID</code> request header.
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> send(String message, @Nullable String event, @Nullable String eventID);

    /**
     * <p>Stops the event stream.</p>
     * <p><strong>Warning:</strong> most clients will reconnect several seconds after this message is called. To prevent that
     * happening, close the stream from the client or on the next request return a <code>204 No Content</code> to the client.</p>
     */
    void close();

    /**
     * Sends a comment to the client. Clients will ignore this, however it can be used as a way to keep the connection alive.
     *
     * @param comment A single-line string to send as a comment.
     * @return completion stage that completes when the comment has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> sendComment(String comment);

    /**
     * <p>Sends a message to the client instructing it to reconnect after the given time period in case of any disconnection
     * (including calling {@link #close()} from the server). A common default (controlled by the client) is several seconds.</p>
     * <p>Note: clients could ignore this value.</p>
     *
     * @param timeToWait The time the client should wait before attempting to reconnect in case of any disconnection.
     * @param unit       The unit of time.
     * @return completion stage that completes when the event has been sent. If there is a problem during sending of
     * an event, completion stage will be completed exceptionally.
     */
    CompletionStage<?> setClientReconnectTime(long timeToWait, TimeUnit unit);

    /**
     * Add a listener for when request processing is complete. One use of this is to detect early client disconnects
     * so that expensive operations can be cancelled.
     * <p>Check {@link ResponseInfo#completedSuccessfully()} for false for SSE streams that did not complete.</p>
     * @param responseCompleteListener The handler to invoke when the request is complete.
     */
    void setResponseCompleteHandler(ResponseCompleteListener responseCompleteListener);

    /**
     * Checks if this publisher has been closed.
     * <p>This will be true if the server or the client closes the SSE stream.</p>
     * @return true if it is closed; otherwise false
     */
    boolean isClosed();

    /**
     * <p>Creates a new Server-Sent Events publisher. This is designed by be called from within a MuHandler.</p>
     * <p>This will set the content type of the response to <code>text/event-stream</code> and disable caching.</p>
     * <p>The request will also switch to async mode, which means you can use the returned publisher in another thread.</p>
     * <p><strong>IMPORTANT:</strong> The {@link #close()} method must be called when publishing is complete.</p>
     *
     * @param request  The current MuRequest
     * @param response The current MuResponse
     * @return Returns a publisher that can be used to send messages to the client.
     */
    static AsyncSsePublisher start(MuRequest request, MuResponse response) {
        response.contentType(ContentTypes.TEXT_EVENT_STREAM);
        response.headers().set(HeaderNames.CACHE_CONTROL, "no-cache, no-transform");
        AsyncHandle asyncHandle = request.handleAsync();
        AsyncSsePublisherImpl ssePublisher = new AsyncSsePublisherImpl(asyncHandle);
        asyncHandle.addResponseCompleteHandler(info -> ssePublisher.close());
        return ssePublisher;
    }
}

class AsyncSsePublisherImpl implements AsyncSsePublisher {

    private final AsyncHandle asyncHandle;
    private volatile boolean closed = false;

    AsyncSsePublisherImpl(AsyncHandle asyncHandle) {
        this.asyncHandle = asyncHandle;
    }

    @Override
    public CompletionStage<?> send(String message) {
        return send(message, null, null);
    }

    @Override
    public CompletionStage<?> send(String message, @Nullable String event) {
        return send(message, event, null);
    }

    @Override
    public CompletionStage<?> send(String message, @Nullable String event, @Nullable String eventID) {
        return write(SsePublisherImpl.dataText(message, event, eventID));
    }

    @Override
    public CompletionStage<?> sendComment(String comment) {
        return write(SsePublisherImpl.commentText(comment));
    }

    @Override
    public CompletionStage<?> setClientReconnectTime(long timeToWait, TimeUnit unit) {
        return write(SsePublisherImpl.clientReconnectText(timeToWait, unit));
    }

    @Override
    public void setResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
        asyncHandle.addResponseCompleteHandler(responseCompleteListener);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private CompletionStage<?> write(String text) {
        CompletableFuture<?> stage = new CompletableFuture<>();
        if (closed) {
            stage.completeExceptionally(new IllegalStateException("The SSE stream was already closed"));
        } else {
            asyncHandle.write(Mutils.toByteBuffer(text), error -> {
                if (error == null) {
                    stage.complete(null);
                } else {
                    stage.completeExceptionally(error);
                }
            });
        }
        return stage;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            asyncHandle.complete();
        }
    }

}
