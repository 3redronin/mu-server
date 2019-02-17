package io.muserver.handlers;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RangeRequestsTest {

    private MuServer server;

    @Test
    public void rangeRequestsSupported() throws IOException {

        server = httpsServer()
            .addHandler(ResourceHandlerBuilder.classpathHandler("/sample-static").withPathToServeFrom("/cp"))
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").withPathToServeFrom("/fp"))
            .start();

        for (String prefix : new String[]{"cp", "fp"}) {
            URI uri = server.uri().resolve("/" + prefix + "/alphanumerics.txt");
            try (Response resp = call(request(uri))) {
                assertThat(prefix, resp.code(), is(200));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("62"));
                assertThat(prefix, resp.header("Content-Range"), is(nullValue()));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"));
            }

            try (Response resp = call(request(uri).header("Range", "bytes=0-9"))) {
                assertThat(prefix, resp.code(), is(206));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("10"));
                assertThat(prefix, resp.header("Content-Range"), is("bytes 0-9/62"));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("0123456789"));
            }

            try (Response resp = call(request(uri).header("Range", "bytes=10-19"))) {
                assertThat(prefix, resp.code(), is(206));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("10"));
                assertThat(prefix, resp.header("Content-Range"), is("bytes 10-19/62"));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("abcdefghij"));
            }

            for (String lastTenBytes : new String[]{"bytes=-10", "bytes=52-61"}) {
                try (Response resp = call(request(uri).header("Range", lastTenBytes))) {
                    assertThat(prefix, resp.code(), is(206));
                    assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                    assertThat(prefix, resp.header("Content-Length"), is("10"));
                    assertThat(prefix, resp.header("Content-Range"), is("bytes 52-61/62"));
                    assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                    assertThat(prefix, resp.body().string(), is("QRSTUVWXYZ"));
                }
            }

        }


    }


    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }


}
