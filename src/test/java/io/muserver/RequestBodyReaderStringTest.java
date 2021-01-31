package io.muserver;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;
import scaffolding.SlowBodySender;

import java.io.IOException;
import java.nio.charset.Charset;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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

        Request.Builder request = request()
            .url(server.uri().toString())
            .post(new SlowBodySender(2));

        try (Response resp = call(request)) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Loop 0\nLoop 1\n"));
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

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}