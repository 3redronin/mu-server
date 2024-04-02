package io.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

/**
 * <p>An interface for sending Server-Sent Events (SSE) to a client.</p>
 * <p>The following example creates a publisher and publishes 10 messages to it from another thread:</p>
 * <pre><code>
 * server = httpsServer()
 *     .addHandler(Method.GET, "/streamer", (request, response, pathParams) -&gt; {
 *         SsePublisher ssePublisher = SsePublisher.start(request, response);
 *         new Thread(() -&gt; {
 *             try {
 *                 for (int i = 0; i &lt; 100; i++) {
 *                     ssePublisher.send("This is message " + i);
 *                     Thread.sleep(1000);
 *                 }
 *             } catch (Exception e) {
 *                 // the user has probably disconnected; stop publishing
 *             } finally {
 *                 ssePublisher.close();
 *             }
 *         }).start();
 *
 *     })
 *     .start();
 * </code></pre>
 */
public interface SsePublisher {

    /**
     * Sends a message (without an ID or event type)
     * @param message The message to send
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void send(String message) throws IOException;

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
     * @param event An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void send(String message, String event) throws IOException;

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
     * @param event An event name. If <code>null</code> is specified, clients default to a message type of <code>message</code>
     * @param eventID An identifier for the message. If set, and the browser reconnects, then the last event ID will be
     *                sent by the browser in the <code>Last-Event-ID</code> request header.
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void send(String message, String event, String eventID) throws IOException;

    /**
     * <p>Stops the event stream.</p>
     * <p><strong>Warning:</strong> most clients will reconnect several seconds after this message is called. To prevent that
     * happening, close the stream from the client or on the next request return a <code>204 No Content</code> to the client.</p>
     */
    void close();

    /**
     * Sends a comment to the client. Clients will ignore this, however it can be used as a way to keep the connection alive.
     * @param comment A single-line string to send as a comment.
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void sendComment(String comment) throws IOException;

    /**
     * <p>Sends a message to the client instructing it to reconnect after the given time period in case of any disconnection
     * (including calling {@link #close()} from the server). A common default (controlled by the client) is several seconds.</p>
     * <p>Note: clients could ignore this value.</p>
     * @param timeToWait The time the client should wait before attempting to reconnect in case of any disconnection.
     * @param unit The unit of time.
     * @throws IOException Thrown if there is an error writing to the client, for example if the user has closed their browser.
     */
    void setClientReconnectTime(long timeToWait, TimeUnit unit) throws IOException;

    /**
     * <p>Creates a new Server-Sent Events publisher. This is designed by be called from within a MuHandler.</p>
     * <p>This will set the content type of the response to <code>text/event-stream</code> and disable caching.</p>
     * <p>The request will also switch to async mode, which means you can use the returned publisher in another thread.</p>
     * <p><strong>IMPORTANT:</strong> The {@link #close()} method must be called when publishing is complete.</p>
     * @param request The current MuRequest
     * @param response The current MuResponse
     * @return Returns a publisher that can be used to send messages to the client.
     */
    static SsePublisher start(MuRequest request, MuResponse response) {
        response.contentType(ContentTypes.TEXT_EVENT_STREAM);
        response.headers().set(HeaderNames.CACHE_CONTROL, "no-cache, no-transform");
        return new SsePublisherImpl(request.handleAsync());
    }
}

class SsePublisherImpl implements SsePublisher {

    private final AsyncHandle asyncHandle;

    SsePublisherImpl(AsyncHandle asyncHandle) {
        this.asyncHandle = asyncHandle;
    }

    @Override
    public void send(String message) throws IOException {
        send(message, null, null);
    }

    @Override
    public void send(String message, String event) throws IOException {
        send(message, event, null);
    }

    @Override
    public void send(String message, String event, String eventID) throws IOException {
        sendChunk(dataText(message, event, eventID));
    }

    @Override
    public void sendComment(String comment) throws IOException {
        sendChunk(commentText(comment));
    }

    @Override
    public void setClientReconnectTime(long timeToWait, TimeUnit unit) throws IOException {
        sendChunk(clientReconnectText(timeToWait, unit));
    }

    @Override
    public void close() {
        asyncHandle.complete();
    }

    private void sendChunk(String text) throws IOException {
        try {
            ByteBuffer buf = Mutils.toByteBuffer(text);
            asyncHandle.write(buf).get();
        } catch (Throwable e) {
            close();
            throw new IOException("Error while publishing SSE message", e);
        }
    }

    private static void ensureNoLineBreaks(String value, String thing) {
        if (containsLinebreak(value)) {
            throw new IllegalArgumentException(thing + " cannot have new line characters in them");
        }
    }

    private static boolean containsLinebreak(String value) {
        return value.contains("\n") || value.contains("\r");
    }

    static String dataText(String message, String event, String eventID) {
        StringBuilder raw = new StringBuilder();
        if (eventID != null) {
            ensureNoLineBreaks(eventID, "SSE IDs");
            raw.append("id: ").append(eventID).append('\n');
        }
        if (event != null) {
            ensureNoLineBreaks(event, "SSE event names");
            raw.append("event: ").append(event).append('\n');
        }
        if (containsLinebreak(message)) {
            String[] lines = message.split("(\r\n)|[\r\n]"); // this is quite expensive if the string is big
            for (String line : lines) {
                raw.append("data: ").append(line).append('\n');
            }
        } else {
            raw.append("data: ").append(message).append('\n');
        }
        raw.append('\n');
        return raw.toString();
    }

    static String commentText(String comment) {
        ensureNoLineBreaks(comment, "SSE Comments");
        return ":" + comment + "\n\n";
    }

    static String clientReconnectText(long timeToWait, TimeUnit unit) {
        return "retry: " + unit.toMillis(timeToWait) + '\n';
    }
}
