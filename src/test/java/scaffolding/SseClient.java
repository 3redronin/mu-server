/*
 *  Copyright (c) 2016 HERE Europe B.V.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */




// This was just copied from https://github.com/heremaps/oksse as it's not in maven central.



package scaffolding;

import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class SseClient {

    /**
     * OkSse is a plugin for OkHttp library that extends its functionality to create a Server Sent Event client.
     * <p>
     * The usage of this class follows the same logic as any OkHttp request.
     * <p>
     * <p>Use {@code new OkSse()} to create a new instance with a new instance of {@link OkHttpClient} with default settings
     * <pre>   {@code
     *
     *   // The singleton HTTP client.
     *   public final OkSse okSseClient = new OkSse();
     * }</pre>
     * <p>
     * <p>Use {@code new OkSse(okHttpClient)} to create a new instance that shares the instance of {@link OkHttpClient}.
     * This would be the prefered way, since the resources of the OkHttpClient will be reused for the SSE
     * <pre>   {@code
     *
     *   // The singleton HTTP client.
     *   public final OkSse okSseClient = new OkSse(okHttpClient);
     * }</pre>
     * <p>
     * To create a new {@link ServerSentEvent} call {@link OkSse#newServerSentEvent(Request, ServerSentEvent.Listener)}
     * giving the desired {@link Request}. Note that must be a GET request.
     * <p>
     * OkSse will make sure to build the proper parameters needed for SSE conneciton and return the instance.
     */
    public static class OkSse {

        private final OkHttpClient client;

        /**
         * Create a OkSse using a new instance of {@link OkHttpClient} with the default settings.
         */
        public OkSse() {
            this(new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(true).build());
        }

        /**
         * Creates a new OkSse using the shared {@link OkHttpClient}
         *
         * @param client
         */
        public OkSse(OkHttpClient client) {
            this.client = client.newBuilder().protocols(Collections.singletonList(Protocol.HTTP_1_1)).build();
        }

        /**
         * Get the {@link OkHttpClient} used to create this instance.
         *
         * @return the instance of the {@link OkHttpClient}
         */
        public OkHttpClient getClient() {
            return client;
        }

        /**
         * Create a new instance of {@link ServerSentEvent} that will handle the connection and communication with
         * the SSE Server.
         *
         * @param request  the OkHttp {@link Request} with the valid information to create the connection with the server.
         * @param listener the {@link ServerSentEvent.Listener} to attach to this SSE.
         * @return a new instance of {@link ServerSentEvent} that will automatically start the connection.
         */
        public ServerSentEvent newServerSentEvent(Request request, ServerSentEvent.Listener listener) {
            RealServerSentEvent sse = new RealServerSentEvent(request, listener);
            sse.connect(client);
            return sse;
        }
    }

    static class RealServerSentEvent implements ServerSentEvent {

        private final Listener listener;
        private final Request originalRequest;

        private OkHttpClient client;
        private Call call;
        private Reader sseReader;

        private long reconnectTime = TimeUnit.SECONDS.toMillis(3);
        private long readTimeoutMillis = 0;
        private String lastEventId;

        RealServerSentEvent(Request request, Listener listener) {
            if (!"GET".equals(request.method())) {
                throw new IllegalArgumentException("Request must be GET: " + request.method());
            }
            this.originalRequest = request;
            this.listener = listener;
        }

        void connect(OkHttpClient client) {
            this.client = client;
            prepareCall(originalRequest);
            enqueue();
        }

        private void prepareCall(Request request) {
            if (client == null) {
                throw new AssertionError("Client is null");
            }
            Request.Builder requestBuilder = request.newBuilder()
                .header("Accept-Encoding", "")
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");

            if (lastEventId != null) {
                requestBuilder.header("Last-Event-Id", lastEventId);
            }

            call = client.newCall(requestBuilder.build());
        }

        private void enqueue() {
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    notifyFailure(e, null);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        openSse(response);
                    } else {
                        notifyFailure(new IOException(response.message()), response);
                    }
                }
            });
        }

        private void openSse(Response response) {
            sseReader = new Reader(response.body().source());
            sseReader.setTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS);
            listener.onOpen(this, response);

            //noinspection StatementWithEmptyBody
            while (call != null && !call.isCanceled() && sseReader.read()) {
            }
        }

        private void notifyFailure(Throwable throwable, Response response) {
            if (!retry(throwable, response)) {
                listener.onClosed(this);
                close();
            }
        }

        private boolean retry(Throwable throwable, Response response) {
            if (!Thread.currentThread().isInterrupted() && !call.isCanceled() && listener.onRetryError(this, throwable, response)) {
                Request request = listener.onPreRetry(this, originalRequest);
                if (request == null) {
                    return false;
                }
                prepareCall(request);
                try {
                    Thread.sleep(reconnectTime);
                } catch (InterruptedException ignored) {
                    return false;
                }
                if (!Thread.currentThread().isInterrupted() && !call.isCanceled()) {
                    enqueue();
                    return true;
                }
            }
            return false;
        }

        @Override
        public Request request() {
            return originalRequest;
        }

        @Override
        public void setTimeout(long timeout, TimeUnit unit) {
            if (sseReader != null) {
                sseReader.setTimeout(timeout, unit);
            }
            readTimeoutMillis = unit.toMillis(timeout);
        }

        @Override
        public void close() {
            if (call != null && !call.isCanceled()) {
                call.cancel();
            }

        }

        /**
         * Internal reader for the SSE channel. This will wait for data being send and will parse it according to the
         * SSE standard.
         *
         * @see Reader#read()
         */
        private class Reader {

            private static final char COLON_DIVIDER = ':';
            private static final String UTF8_BOM = "\uFEFF";

            private static final String DATA = "data";
            private static final String ID = "id";
            private static final String EVENT = "event";
            private static final String RETRY = "retry";
            private static final String DEFAULT_EVENT = "message";
            private static final String EMPTY_STRING = "";

            private final Pattern DIGITS_ONLY = Pattern.compile("^[\\d]+$");

            private final BufferedSource source;

            // Intentionally done to reuse StringBuilder for memory optimization
            @SuppressWarnings("PMD.AvoidStringBufferField")
            private StringBuilder data = new StringBuilder();
            private String eventName = DEFAULT_EVENT;

            Reader(BufferedSource source) {
                this.source = source;
            }

            /**
             * Blocking call that will try to read a line from the source
             *
             * @return true if the read was successfully, false if an error was thrown
             */
            boolean read() {
                try {
                    String line = source.readUtf8LineStrict();
                    processLine(line);
                } catch (IOException e) {
                    notifyFailure(e, null);
                    return false;
                }
                return true;
            }

            /**
             * Sets a reading timeout, so the read operation will get unblock if this timeout is reached.
             *
             * @param timeout timeout to set
             * @param unit    unit of the timeout to set
             */
            void setTimeout(long timeout, TimeUnit unit) {
                if (source != null) {
                    source.timeout().timeout(timeout, unit);
                }
            }

            private void processLine(String line) {
                //log("Sse read line: " + line);
                if (line == null || line.isEmpty()) { // If the line is empty (a blank line). Dispatch the event.
                    dispatchEvent();
                    return;
                }

                int colonIndex = line.indexOf(COLON_DIVIDER);
                if (colonIndex == 0) { // If line starts with COLON dispatch a comment
                    listener.onComment(RealServerSentEvent.this, line.substring(1).trim());
                } else if (colonIndex != -1) { // Collect the characters on the line after the first U+003A COLON character (:), and let value be that string.
                    String field = line.substring(0, colonIndex);
                    String value = EMPTY_STRING;
                    int valueIndex = colonIndex + 1;
                    if (valueIndex < line.length()) {
                        if (line.charAt(valueIndex) == ' ') { // If value starts with a single U+0020 SPACE character, remove it from value.
                            valueIndex++;
                        }
                        value = line.substring(valueIndex);
                    }
                    processField(field, value);
                } else {
                    processField(line, EMPTY_STRING);
                }
            }

            private void dispatchEvent() {
                if (data.length() == 0) {
                    return;
                }
                String dataString = data.toString();
                if (dataString.endsWith("\n")) {
                    dataString = dataString.substring(0, dataString.length() - 1);
                }
                listener.onMessage(RealServerSentEvent.this, lastEventId, eventName, dataString);
                data.setLength(0);
                eventName = DEFAULT_EVENT;
            }

            private void processField(String field, String value) {
                if (DATA.equals(field)) {
                    data.append(value).append('\n');
                } else if (ID.equals(field)) {
                    lastEventId = value;
                } else if (EVENT.equals(field)) {
                    eventName = value;
                } else if (RETRY.equals(field) && DIGITS_ONLY.matcher(value).matches()) {
                    long timeout = Long.parseLong(value);
                    if (listener.onRetryTime(RealServerSentEvent.this, timeout)) {
                        reconnectTime = timeout;
                    }
                }
            }
        }
    }

    public interface ServerSentEvent {

        /**
         * @return the original request that initiated the Server Sent Event.
         */
        Request request();

        /**
         * Defines a timeout to close the connection when now events are received. This is useful to avoid hanging connections.
         * Note if this is set a heartbeat mechanism should be implemented in server side to avoid closing connections when no events.
         *
         * @param timeout timeout to set
         * @param unit    the {@link TimeUnit} of the timeout to set
         */
        void setTimeout(long timeout, TimeUnit unit);

        /**
         * Force the Server Sent event channel to close. This will cancel any pending request or close the established channel.
         */
        void close();

        interface Listener {

            /**
             * Notify when the connection is open an established. From this point on, new message could be received.
             *
             * @param sse      the instance of {@link ServerSentEvent}
             * @param response the response from the server after establishing the connection
             */
            void onOpen(ServerSentEvent sse, Response response);

            /**
             * Called every time a message is received.
             *
             * @param sse     the instance of {@link ServerSentEvent}
             * @param id      id sent by the server to identify the message
             * @param event   event type of this message
             * @param message message payload
             */
            void onMessage(ServerSentEvent sse, String id, String event, String message);

            /**
             * Called every time a comment is received.
             *
             * @param sse     the instance of {@link ServerSentEvent}
             * @param comment the content of the comment
             */
            void onComment(ServerSentEvent sse, String comment);

            /**
             * The stream can define the retry time sending a message with "retry: milliseconds"
             * If this event is received this method will be called with the sent value
             *
             * @param sse          the instance of {@link ServerSentEvent}
             * @param milliseconds new retry time in milliseconds
             * @return true if this retry time should be used, false otherwise
             */
            boolean onRetryTime(ServerSentEvent sse, long milliseconds);

            /**
             * Notify when the connection failed either because it could not be establish or the connection broke.
             * The Server Sent Event protocol defines that should be able to reestablish a connection using retry mechanism.
             * In some cases depending on the error the connection should not be retry.
             * <p>
             * Implement this method to define this behavior.
             *
             * @param sse       the instance of {@link ServerSentEvent}
             * @param throwable the instance of the error that caused the failure
             * @param response  the response of the server that caused the failure, it might be null.
             * @return true if the connection should be retried after the defined retry time, false to avoid the retry, this will close the SSE.
             */
            boolean onRetryError(ServerSentEvent sse, Throwable throwable, Response response);

            /**
             * Notify that the connection was closed.
             *
             * @param sse the instance of {@link ServerSentEvent}
             */
            void onClosed(ServerSentEvent sse);

            /**
             * Notifies client before retrying to connect. At this point listener may decide to return
             * {@code originalRequest} to repeat last request or another one to alternate
             *
             * @param sse             the instance of {@link ServerSentEvent}
             * @param originalRequest the request to be retried
             * @return call to be executed or {@code null} to cancel retry and close the SSE channel
             */
            Request onPreRetry(ServerSentEvent sse, Request originalRequest);
        }
    }

}