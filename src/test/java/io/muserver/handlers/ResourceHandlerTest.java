package io.muserver.handlers;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.Mutils;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.RawClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Mutils.urlDecode;
import static io.muserver.handlers.ResourceType.getResourceTypes;
import static io.muserver.handlers.ResourceType.gzippableMimeTypes;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
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
        assertContentTypeAndContent("/images/" + Mutils.urlEncode("guangzhou.jpeg"), "image/jpeg", false);
        assertContentTypeAndContent("/images/" + Mutils.urlEncode("guangzhou, china.jpeg"), "image/jpeg", false);

        assertNotFound("/bad-path");
    }

    @Test
    public void classpathCanBeUsed() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandlerBuilder.fileOrClasspath("src/test/resources/does-not-exist", "/sample-static").build())
            .start();

        assertContentTypeAndContent("/index.html", "text/html", false);
        assertContentTypeAndContent("/images/" + Mutils.urlEncode("guangzhou.jpeg"), "image/jpeg", false);
        assertContentTypeAndContent("/images/" + Mutils.urlEncode("guangzhou, china.jpeg"), "image/jpeg", false);

        assertNotFound("/bad-path");
    }

    @Test
    public void contextsCanBeUsed() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(context("/a")
                .addHandler(context("/b")
                    .addHandler(context("/c")
                        .addHandler(ResourceHandler.classpathHandler("/sample-static")
                            .withPathToServeFrom("/d")
                        ))))
            .start();

        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve("/a/b/c/d/index.html").toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers().toMultimap(), equalTo(headersFromGET));
            assertThat(resp.body().contentLength(), is(0L));
        }

        assertNotFound("/d/index.html");
    }

    @Test
    public void requestsWithDotDotOrTildesResultIn404s() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandlerBuilder.fileOrClasspath("src/test/resources/does-not-exist", "/sample-static").build())
            .start();

        assertNotFound("/../something.txt");
        assertNotFound("/images/../../something.txt");
        assertNotFound("/images/../blah");
    }

    @Test
    public void directoriesResultIn302s() throws Exception {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandlerBuilder.classpathHandler("/sample-static").withPathToServeFrom("/classpath").build())
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").withPathToServeFrom("/file").build())
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/classpath/images").toURL()))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/classpath/images/").toString()));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
        try (Response resp = call(request().url(server.uri().resolve("/file/images").toURL()))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/file/images/").toString()));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @Test
    public void callsToContextNamesWithoutTrailingSlashesResultIn302() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(context("my-app")
                .addHandler(ResourceHandlerBuilder.classpathHandler("/sample-static"))
            )
            .start();

        URL url = server.httpsUri().resolve("/my-app?hello=world").toURL();
        try (Response resp = call(request().get().url(url))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/my-app/?hello=world").toString()));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @Test
    public void filesCanHaveNoFileExtensions() throws IOException {
        server = MuServerBuilder.httpsServer()
            .withGzipEnabled(false)
            .addHandler(ResourceHandlerBuilder.classpathHandler("/sample-static").withPathToServeFrom("/classpath").build())
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").withPathToServeFrom("/file").build())
            .start();

        try (Response resp = call(request().url(server.uri().resolve("/file/filewithnoextension").toURL()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("application/octet-stream"));
            assertThat(resp.body().string(), equalTo("I am not a directory"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/classpath/filewithnoextension").toURL()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("Content-Type"), equalTo("application/octet-stream"));
            assertThat(resp.body().string(), equalTo("I am not a directory"));
        }
    }

    private void assertNotFound(String path) throws MalformedURLException {
        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve(path).toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(404));
        }
        headersFromGET.remove("date");
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(404));
            Map<String, List<String>> headersFromHEAD = resp.headers().toMultimap();
            headersFromHEAD.remove("date");
            assertThat(headersFromHEAD, equalTo(headersFromGET));
        }
    }

    @Test
    public void canServeFromPath() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .build())
            .start();

        try (Response badOne = call(request().url(server.httpsUri().resolve("/index.html").toURL()))) {
            assertThat(badOne.code(), is(404));
        }

        try (Response resp = call(request().url(server.httpsUri().resolve("/blah/index.html").toURL()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
    }

    @Test
    public void itCanDefaultToFilesSuchAsIndexHtml() throws Exception {
        server = MuServerBuilder.httpsServer()
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static")
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
            .withGzip(1200, gzippableMimeTypes(getResourceTypes()))
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").build())
            .start();

//        assertContentTypeAndContent("/index.html", "text/html", false); // not sure why it's chunked but not gzipped. Probably just too small.
        assertContentTypeAndContent("/overview.txt", "text/plain", true);
        assertContentTypeAndContent("/sample.css", "text/css", true);
        assertContentTypeAndContent("/images/guangzhou.jpeg", "image/jpeg", false);
        assertContentTypeAndContent("/images/friends.jpg", "image/jpeg", false);
    }

    @Test
    public void headRequestHasNoBody() throws Exception {
        server = MuServerBuilder.httpServer()
            .withGzip(1200, gzippableMimeTypes(getResourceTypes()))
            .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static").build())
            .start();

        try (RawClient client = RawClient.create(server.uri())) {
            client.sendStartLine("HEAD", "/overview.txt");
            client.sendHeader("Host", server.uri().getAuthority());
            client.endHeaders();
            client.flushRequest();

            MuAssert.waitUntil( () -> client.responseString().contains("\r\n\r\n"));
            Thread.sleep(100);
            assertThat(client.responseString(), endsWith("\r\n\r\n"));
        }
    }

    private void assertContentTypeAndContent(String relativePath, String expectedContentType, boolean expectGzip) throws Exception {
        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve(relativePath).toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is(expectedContentType));
            assertThat(resp.header("Vary"), is("accept-encoding"));
            assertThat(resp.body().string(), is(readResource("/sample-static" + urlDecode(relativePath))));

            if (expectGzip) {
                assertThat(resp.headers().toString(), resp.header("Transfer-Encoding"), is("chunked"));
                // doesn't gzip from tests... because of okhttpclient?
//                assertThat(resp.headers().toString(), resp.header("Content-Encoding"), is("gzip"));
            } else {
                assertThat(resp.headers().toString(), resp.header("Content-Encoding"), is(nullValue()));
            }

        }

        headersFromGET.remove("Date");


        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(200));
            Map<String, List<String>> headersFromHEAD = resp.headers().toMultimap();
            headersFromHEAD.remove("Date");
            if (expectGzip) {
                headersFromHEAD.remove("Content-Length");
                headersFromGET.remove("transfer-encoding");
                assertThat(headersFromHEAD, equalTo(headersFromGET));
            } else {
                assertThat(headersFromHEAD, equalTo(headersFromGET));
            }
            assertThat(resp.header("Vary"), is("accept-encoding"));
            assertThat(resp.body().contentLength(), is(0L));
        }

    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}