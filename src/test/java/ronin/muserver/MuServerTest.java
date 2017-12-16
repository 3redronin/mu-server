package ronin.muserver;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static ronin.muserver.MuServerBuilder.muServer;
import static ronin.muserver.SyncHandlerAdapter.syncHandler;

public class MuServerTest {

    private final OkHttpClient client = new OkHttpClient();

    @Test
    public void asyncHandlersSupported() throws IOException, InterruptedException {
        MuServer muServer = muServer()
            .withHttpConnection(12808)
            .withHandlers(
                new MuHandler() {
                    public boolean onHeaders(AsyncContext ctx) throws Exception {
                        System.out.println("I am a logging handler and saw " + ctx.request);
                        return false;
                    }

                    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
                    }

                    public void onRequestComplete(AsyncContext ctx) {
                    }
                },
                new MuHandler() {
                    @Override
                    public boolean onHeaders(AsyncContext ctx) throws Exception {
                        System.out.println("Request starting");
                        ctx.response.status(201);
                        return true;
                    }

                    @Override
                    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
                        String text = StandardCharsets.UTF_8.decode(buffer).toString();
                        System.out.println("Got: " + text);
                        ctx.response.writeAsync(text);
                    }

                    @Override
                    public void onRequestComplete(AsyncContext ctx) {
                        System.out.println("Request complete");
                        ctx.complete();
                    }
                },
                new MuHandler() {
                    public boolean onHeaders(AsyncContext ctx) throws Exception {
                        throw new RuntimeException("This should never get here");
                    }

                    public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
                    }

                    public void onRequestComplete(AsyncContext ctx) {
                    }
                })
            .start();

        StringBuffer expected = new StringBuffer();
        RequestBody requestBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.parse("text/plain");
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                write(sink, "Numbers\n");
                write(sink, "-------\n");
                for (int i = 2; i <= 997; i++) {
                    write(sink, String.format(" * %s\n", i));
                }
            }

            private void write(BufferedSink sink, String s) throws IOException {
                expected.append(s);
                sink.writeUtf8(s);
            }

        };

        Response resp = client.newCall(new Request.Builder()
            .url("http://localhost:12808")
            .post(requestBody)
            .build()).execute();

        muServer.stop();

        assertThat(resp.code(), is(201));
        assertThat(resp.body().string(), equalTo(expected.toString()));
    }

    @Test
    public void syncHandlersSupported() throws IOException, InterruptedException {
        MuServer muServer = muServer()
            .withHttpConnection(12808)
            .withHandlers(
                route(HttpMethod.GET, "/blah", syncHandler((request, response) -> {
                    System.out.println("Running sync handler");
                    response.status(202);
                    response.write("This is a test");
                    System.out.println("Sync handler complete");
                    return true;
                })))
            .start();

        Response resp = client.newCall(new Request.Builder()
            .url("http://localhost:12808/blah")
            .build()).execute();

        muServer.stop();

        assertThat(resp.code(), is(202));
        assertThat(resp.body().string(), equalTo("This is a test"));
    }

    private static MuHandler route(HttpMethod method, String path, MuHandler muHandler) {
        return new MuHandler() {
            public boolean onHeaders(AsyncContext ctx) throws Exception {
                if (!ctx.request.uri().getPath().equals(path) && ctx.request.method() == method)
                    return false;
                muHandler.onHeaders(ctx);
                return true;
            }

            public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
                muHandler.onRequestData(ctx, buffer);
            }

            public void onRequestComplete(AsyncContext ctx) {
                muHandler.onRequestComplete(ctx);
            }
        };
    }

}