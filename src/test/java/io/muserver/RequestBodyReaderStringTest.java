package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;
import scaffolding.StringUtils;

import javax.ws.rs.ClientErrorException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RequestBodyReaderStringTest {
    private MuServer server;

    @Test
    public void requestBodiesCanBeReadAsStrings() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.write(request.readBodyAsString());
                return true;
            })
            .start();

        int messagesToSend = 200;
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(messagesToSend, 2));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < messagesToSend; i++) {
                expected.append("Loop " + i +"\n");
            }
            assertThat(resp.body().string(), equalTo(expected.toString()));
        }
    }

    @Test
    public void emptyStringsAreOkay() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(String.valueOf(requestBody.length()));
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create("", MediaType.get("text/plain")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("0"));
        }
    }

    @Test
    public void stringsInNonUTF8AreFine() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create("ЧАСТЬ ПЕРВАЯ.".getBytes(Charset.forName("ISO-8859-5")), MediaType.get("text/plain;charset=ISO-8859-5")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("ЧАСТЬ ПЕРВАЯ."));
        }
    }


    @Test
    public void chineseWorks() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                String requestBody = request.readBodyAsString();
                response.write(requestBody);
                return true;
            })
            .start();
        Request.Builder request = request()
            .url(server.uri().toString())
            .post(RequestBody.create("怎么样", MediaType.get("text/plain;charset=UTF-8")));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("怎么样"));
            assertThat(resp.body().contentLength(), is(9L));
        }
    }

    @Test
    public void aSlowReadResultsInACompleted408IfResponseNotStarted() {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withRequestTimeout(100, TimeUnit.MILLISECONDS)
            .addHandler((request, response) -> {
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(2, 200));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(408));
            assertThat(resp.body().string(), containsString("408 Request Timeout"));
        } catch (Exception e) {
            // The HttpServerKeepAliveHandler will probably close the connection before the full request body is read, which is probably a good thing in this case.
            // So allow a valid 408 response or an error
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        assertThat(exception.get(), instanceOf(ClientErrorException.class));
        assertThat(((ClientErrorException)exception.get()).getResponse().getStatus(), equalTo(408));
    }

    @Test
    public void exceedingUploadSizeResultsIn413ForChunkedRequest() throws Exception {
        AtomicReference<Throwable> exception = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest()
            .withMaxRequestSize(1000)
            .addHandler((request, response) -> {
                try {
                    request.readBodyAsString();
                } catch (Throwable e) {
                    exception.set(e);
                    throw e;
                }
                return true;
            })
            .start();

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(1000, 0));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(413));
            assertThat(resp.body().string(), containsString("413 Payload Too Large"));
        }
        assertThat(exception.get(), instanceOf(ClientErrorException.class));
        assertThat(((ClientErrorException)exception.get()).getResponse().getStatus(), equalTo(408));
    }



    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}