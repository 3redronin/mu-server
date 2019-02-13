package scaffolding;

import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class TestSseClient implements SseClient.ServerSentEvent.Listener {
    public final List<String> receivedMessages = new ArrayList<>();
    private CountDownLatch completedLatch = new CountDownLatch(1);
    private Response response;

    @Override
    public void onOpen(SseClient.ServerSentEvent sse, Response response) {
        receivedMessages.add("open");
        this.response = response;
    }

    @Override
    public void onMessage(SseClient.ServerSentEvent sse, String id, String event, String message) {
        receivedMessages.add("message=" + message + "        event=" + event + "        id=" + id);
    }

    @Override
    public void onComment(SseClient.ServerSentEvent sse, String comment) {
        receivedMessages.add("comment=" + comment);
    }

    @Override
    public boolean onRetryTime(SseClient.ServerSentEvent sse, long milliseconds) {
        receivedMessages.add("retryTime=" + milliseconds);
        return true;
    }

    @Override
    public boolean onRetryError(SseClient.ServerSentEvent sse, Throwable throwable, Response response) {
        receivedMessages.add("retryError");
        return false;
    }

    @Override
    public void onClosed(SseClient.ServerSentEvent sse) {
        receivedMessages.add("closed");
        completedLatch.countDown();
    }

    @Override
    public Request onPreRetry(SseClient.ServerSentEvent sse, Request originalRequest) {
        receivedMessages.add("onPreRetry");
        return originalRequest;
    }

    public void cleanup() {
        if (response != null && response.body() != null) {
            response.body().close();
        }
    }

    public void assertListenerIsClosed() throws InterruptedException {
        assertThat("Timed out waiting for SSE stream to close",
            completedLatch.await(1, TimeUnit.MINUTES), is(true));
    }
}
