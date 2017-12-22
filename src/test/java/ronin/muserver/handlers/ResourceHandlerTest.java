package ronin.muserver.handlers;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import ronin.muserver.MuServer;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static ronin.muserver.MuServerBuilder.httpsServer;
import static ronin.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.FileUtils.readResource;

public class ResourceHandlerTest {

    private MuServer server;

    @Test
    public void canServeFromRootOfServer() throws Exception {
        server = httpsServer()
            .addHandler(new ResourceHandler("src/test/resources/sample-static", "/", "index.html", DEFAULT_EXTENSION_MAPPINGS))
            .start();

        assertContentTypeAndContent("/index.html", "text/html");
    }

    @Test
    public void canServeFromPath() throws Exception {
        server = httpsServer()
            .addHandler(new ResourceHandler("src/test/resources/sample-static", "/blah", "index.html", DEFAULT_EXTENSION_MAPPINGS))
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
            .addHandler(new ResourceHandler("src/test/resources/sample-static", "/blah", "index.html", DEFAULT_EXTENSION_MAPPINGS))
            .start();

        Response resp = call(request().url(server.httpsUri().resolve("/blah/").toURL()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Type"), is("text/html"));
        assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
    }

    @Test
    public void contentTypesAreCorrect() throws Exception {
        server = httpsServer()
            .addHandler(new ResourceHandler("src/test/resources/sample-static", "/", null, ResourceType.DEFAULT_EXTENSION_MAPPINGS))
            .start();
        assertContentTypeAndContent("/index.html", "text/html");
        assertContentTypeAndContent("/sample.css", "text/css");
        assertContentTypeAndContent("/images/guangzhou.jpeg", "image/jpeg");
        assertContentTypeAndContent("/images/friends.jpg", "image/jpeg");
    }

    private void assertContentTypeAndContent(String relativePath, String expectedContentType) throws IOException {
        Response resp = call(request().url(server.httpsUri().resolve(relativePath).toURL()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Type"), is(expectedContentType));
        assertThat(resp.body().string(), is(readResource("/sample-static" + relativePath)));
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}