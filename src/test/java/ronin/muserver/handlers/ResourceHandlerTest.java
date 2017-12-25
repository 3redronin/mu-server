package ronin.muserver.handlers;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import ronin.muserver.MuServer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static ronin.muserver.handlers.ResourceType.getResourceTypes;
import static ronin.muserver.handlers.ResourceType.gzippableMimeTypes;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.FileUtils.readResource;

public class ResourceHandlerTest {

    private MuServer server;

    @Test
    public void canServeFromRootOfServer() throws Exception {
        server = httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", false);

        try (Response resp = call(request().url(server.httpsUri().resolve("/bad-path").toURL()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void classpathCanBeUsed() throws Exception {
        server = httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandler.fileOrClasspath("src/test/resources/does-not-exist", "/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", false);

        try (Response resp = call(request().url(server.httpsUri().resolve("/bad-path").toURL()))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void canServeFromPath() throws Exception {
        server = httpsServer()
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .build())
            .start();

        Response badOne = call(request().url(server.httpsUri().resolve("/index.html").toURL()));
        assertThat(badOne.code(), is(404));
        badOne.close();

        Response resp = call(request().url(server.httpsUri().resolve("/blah/index.html").toURL()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Type"), is("text/html"));
        assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
    }

    @Test
    public void itCanDefaultToFilesSuchAsIndexHtml() throws Exception {
        server = httpsServer()
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .withDefaultFile("index.html").build())
            .start();

        Response resp = call(request().url(server.httpsUri().resolve("/blah/").toURL()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Type"), is("text/html"));
        assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
    }

    @Test
    public void contentTypesAreCorrect() throws Exception {
        server = httpsServer()
            .withGzip(1, gzippableMimeTypes(getResourceTypes()))
            .addHandler(ResourceHandler.fileHandler("src/test/resources/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", true);
        assertContentTypeAndContent("/sample.css", "text/css", true);
        assertContentTypeAndContent("/images/guangzhou.jpeg", "image/jpeg", false);
        assertContentTypeAndContent("/images/friends.jpg", "image/jpeg", false);
    }

    private void assertContentTypeAndContent(String relativePath, String expectedContentType, boolean expectGzip) throws Exception {
        Response resp = call(request().url(server.httpsUri().resolve(relativePath).toURL()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Type"), is(expectedContentType));
        assertThat(resp.body().string(), is(readResource("/sample-static" + relativePath)));
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