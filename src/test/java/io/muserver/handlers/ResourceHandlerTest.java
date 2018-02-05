package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Headers;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static io.muserver.handlers.ResourceType.getResourceTypes;
import static io.muserver.handlers.ResourceType.gzippableMimeTypes;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.FileUtils.readResource;

public class ResourceHandlerTest {

    private MuServer server;

    @Test
    public void canServeFromRootOfServer() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", false);

        assertNotFound("/bad-path");
    }

    @Test
    public void classpathCanBeUsed() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandler.fileOrClasspath("src/test/resources/does-not-exist", "/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", false);

        assertNotFound("/bad-path");
    }

    private void assertNotFound(String path) throws MalformedURLException {
        Headers headersFromGET;
        URL url = server.httpsUri().resolve(path).toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers();
            assertThat(resp.code(), is(404));
        }
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(404));
            assertThat(resp.headers(), equalTo(headersFromGET));
        }
    }

    @Test
    public void canServeFromPath() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .build())
            .start();

        Response badOne = call(request().url(server.httpsUri().resolve("/index.html").toURL()));
        assertThat(badOne.code(), is(404));
        badOne.close();

        try (Response resp = call(request().url(server.httpsUri().resolve("/blah/index.html").toURL()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
    }

    @Test
    public void itCanDefaultToFilesSuchAsIndexHtml() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .withDefaultFile("index.html").build())
            .start();

        try (Response resp = call(request().url(server.httpsUri().resolve("/blah/").toURL()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
    }

    @Test
    public void contentTypesAreCorrect() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzip(1, gzippableMimeTypes(getResourceTypes()))
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", true);
        assertContentTypeAndContent("/sample.css", "text/css", true);
        assertContentTypeAndContent("/images/guangzhou.jpeg", "image/jpeg", false);
        assertContentTypeAndContent("/images/friends.jpg", "image/jpeg", false);
    }

    private void assertContentTypeAndContent(String relativePath, String expectedContentType, boolean expectGzip) throws Exception {
        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve(relativePath).toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is(expectedContentType));
            assertThat(resp.body().string(), is(readResource("/sample-static" + relativePath)));
        }
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers().toMultimap(), equalTo(headersFromGET));
            assertThat(resp.body().contentLength(), is(0L));
        }


//        if (expectGzip) {
//            assertThat(resp.header("Content-Encoding"), is("gzip"));
//        } else {
//            assertThat(resp.header("Content-Encoding"), is(nullValue()));
//        }
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}