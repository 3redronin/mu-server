package io.muserver;

import okhttp3.internal.concurrent.TaskRunner;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Header;
import okhttp3.internal.http2.Http2Stream;
import okio.Buffer;
import okio.Okio;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.sslContextForTesting;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.MuAssert.stopAndCheck;
import static scaffolding.ServerUtils.httpsServerForTest;

public class Http2CompletionTest {
    private MuServer server;
    private enum RequestEnd { FINISH, RESET, DISCONNECT }

    @After
    public void stop() {
        stopAndCheck(server);
    }

    @Test
    public void completionWaitsForTheRequestBodyToEnd() throws Exception {
        checkCompletion(RequestEnd.FINISH);
    }

    @Test
    public void resettingAnUnfinishedRequestAfterTheResponseReportsFailure() throws Exception {
        checkCompletion(RequestEnd.RESET);
    }

    @Test
    public void disconnectingAfterTheResponseReportsTheUnfinishedRequestAsFailed() throws Exception {
        checkCompletion(RequestEnd.DISCONNECT);
    }

    private void checkCompletion(RequestEnd end) throws Exception {
        CompletableFuture<Boolean> completed = new CompletableFuture<>();
        AtomicInteger notifications = new AtomicInteger();
        CountDownLatch responseEnded = new CountDownLatch(1);
        server = httpsServerForTest()
            .withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .withGzipEnabled(false)
            .addResponseCompleteListener(info -> {
                notifications.incrementAndGet();
                completed.complete(info.completedSuccessfully());
            })
            .addHandler((request, response) -> {
                ((NettyResponseAdaptor) response).addChangeListener((exchange, state) -> {
                    if (state.endState()) {
                        responseEnded.countDown();
                    }
                });
                try (InputStream input = request.inputStream().get()) {
                    assertThat(input.read(), is((int) 'a'));
                }
                response.write("accepted");
                return true;
            }).start();

        try (SSLSocket socket = (SSLSocket) sslContextForTesting(veryTrustingTrustManager)
            .getSocketFactory().createSocket(server.uri().getHost(), server.uri().getPort())) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"h2"});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            assertThat(socket.getApplicationProtocol(), is("h2"));
            try (okhttp3.internal.http2.Http2Connection connection = new okhttp3.internal.http2.Http2Connection
                .Builder(true, TaskRunner.INSTANCE).socket(socket).build()) {
                connection.start();
                Http2Stream stream = connection.newStream(List.of(
                    new Header(":method", "POST"), new Header(":path", "/"),
                    new Header(":scheme", "https"), new Header(":authority", server.uri().getAuthority())), true);
                stream.readTimeout().timeout(5, TimeUnit.SECONDS);
                stream.writeTimeout().timeout(5, TimeUnit.SECONDS);
                stream.getSink().write(new Buffer().writeUtf8("a"), 1);
                stream.getSink().flush();
                assertThat(stream.takeHeaders().get(":status"), is("200"));
                assertThat(Okio.buffer(stream.getSource()).readUtf8(), is("accepted"));
                assertThat(responseEnded.await(5, TimeUnit.SECONDS), is(true));
                // The response has ended, but the client has deliberately left its request open.
                assertThat(notifications.get(), is(0));
                if (end == RequestEnd.RESET) {
                    stream.close(ErrorCode.CANCEL, null);
                } else if (end == RequestEnd.DISCONNECT) {
                    socket.close();
                } else {
                    stream.getSink().close();
                }
                assertThat(completed.get(5, TimeUnit.SECONDS), is(end == RequestEnd.FINISH));
                if (end != RequestEnd.DISCONNECT) {
                    connection.writePingAndAwaitPong();
                }
                assertThat(notifications.get(), is(1));
            }
        }
    }
}
