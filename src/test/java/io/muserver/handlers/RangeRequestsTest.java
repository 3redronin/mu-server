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

    private MuServer server = httpsServer()
        .addHandler(ResourceHandlerBuilder.classpathHandler("/sample-static").withPathToServeFrom("/cp"))
        .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").withPathToServeFrom("/fp"))
        .start();

    @Test
    public void rangeRequestsSupported() throws IOException {

        for (String prefix : new String[]{"cp", "fp"}) {
            URI uri = server.uri().resolve("/" + prefix + "/alphanumerics.txt");

            String lastModified;

            try (Response resp = call(request(uri))) {
                assertThat(prefix, resp.code(), is(200));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("62"));
                assertThat(prefix, resp.header("Content-Range"), is(nullValue()));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"));
                lastModified = resp.header("Last-Modified");
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

            try (Response resp = call(request(uri).header("Range", "bytes=0-9")
                .header("If-Modified-Since", lastModified))) {
                assertThat(prefix, resp.code(), is(304));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("62"));
                assertThat(prefix, resp.header("Content-Range"), is(nullValue()));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
            }

        }

    }

    @Test
    public void ifStartRangeIsTooLargeThen200IsReturned() throws IOException {

        // There is a 416 response code for this status, however the spec notes:
        //       Note: Because servers are free to ignore Range, many
        //      implementations will simply respond with the entire selected
        //      representation in a 200 (OK) response.  That is partly because
        //      most clients are prepared to receive a 200 (OK) to complete the
        //      task (albeit less efficiently) and partly because clients might
        //      not stop making an invalid partial request until they have
        //      received a complete representation.  Thus, clients cannot depend
        //      on receiving a 416 (Range Not Satisfiable) response even when it
        //      is most appropriate.

        for (String prefix : new String[]{"cp", "fp"}) {
            URI uri = server.uri().resolve("/" + prefix + "/alphanumerics.txt");

            try (Response resp = call(request(uri).header("Range", "bytes=70-79"))) {
                assertThat(prefix, resp.code(), is(200));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("62"));
                assertThat(prefix, resp.header("Content-Range"), is(nullValue()));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"));
            }

            try (Response resp = call(request(uri).header("Range", "bytes=10-79"))) {
                assertThat(prefix, resp.code(), is(206));
                assertThat(prefix, resp.header("Content-Type"), is("text/plain"));
                assertThat(prefix, resp.header("Content-Length"), is("52"));
                assertThat(prefix, resp.header("Content-Range"), is("bytes 10-61/62"));
                assertThat(prefix, resp.header("Accept-Ranges"), is("bytes"));
                assertThat(prefix, resp.body().string(), is("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"));
            }

        }

    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }


}
