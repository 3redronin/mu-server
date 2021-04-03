package io.muserver.handlers;

import io.muserver.Headers;
import io.muserver.MuRequest;
import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.Protocol;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Mutils.urlDecode;
import static io.muserver.Mutils.urlEncode;
import static io.muserver.handlers.ResourceHandlerBuilder.*;
import static io.muserver.handlers.ResourceType.getResourceTypes;
import static io.muserver.handlers.ResourceType.gzippableMimeTypes;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.FileUtils.readResource;

public class ResourceHandlerTest {

    private MuServer server;

    @Test
    public void canServeFromRootOfServer() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(fileHandler("src/test/resources/sample-static"))
            .start();

        assertContentTypeAndContent("/index.html", "text/html;charset=utf-8", false);
        assertContentTypeAndContent("/images/" + urlEncode("guangzhou.jpeg"), "image/jpeg", false);
        assertContentTypeAndContent("/images/" + urlEncode("guangzhou, china.jpeg"), "image/jpeg", false);

        assertNotFound("/bad-path");
    }

    @Test
    public void headersCanBeCustomised() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(fileHandler("src/test/resources/sample-static").withResourceCustomizer(new ResourceCustomizer() {
                @Override
                public void beforeHeadersSent(MuRequest request, Headers responseHeaders) {
                    responseHeaders.remove("cache-control");
                    responseHeaders.set("something-else", "here");
                }
            }))
            .start();

        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve("/index.html").toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("cache-control"), is(empty()));
            assertThat(resp.headers("something-else"), contains("here"));
        }
        headersFromGET.remove("Date");
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(200));
            Map<String, List<String>> headersFromHEAD = resp.headers().toMultimap();
            headersFromHEAD.remove("Date");
            assertThat(headersFromHEAD, equalTo(headersFromGET));
        }
    }

    @Test
    public void classpathCanBeUsed() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(fileOrClasspath("src/test/resources/does-not-exist", "/sample-static"))
            .start();

        assertContentTypeAndContent("/index.html", "text/html;charset=utf-8", false);
        assertContentTypeAndContent("/images/" + urlEncode("guangzhou.jpeg"), "image/jpeg", false);
        assertContentTypeAndContent("/images/" + urlEncode("guangzhou, china.jpeg"), "image/jpeg", false);

        assertNotFound("/bad-path");
    }

    @Test
    public void lastModifiedSinceWorks() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("/file").addHandler(fileHandler("src/test/resources/sample-static")))
            .addHandler(context("/classpath").addHandler(classpathHandler("/sample-static")))
            .start();

        String[] dirs = {"file", "classpath"};
        for (String dir : dirs) {
            URI imageUri = server.uri().resolve("/" + dir + "/images/" + urlEncode("guangzhou, china.jpeg"));
            String lastModified;
            try (Response resp = call(request(imageUri))) {
                assertThat(resp.code(), is(200));
                lastModified = resp.header("last-modified");
                assertThat(lastModified, is(notNullValue()));
            }
            try (Response resp = call(request(imageUri).header("If-Modified-Since", lastModified))) {
                assertThat(resp.code(), is(304));
                assertThat(resp.header("last-modified"), is(lastModified));
            }
            Date oneSecBeforeLastModified = new Date(Mutils.fromHttpDate(lastModified).getTime() - 1000);
            try (Response resp = call(request(imageUri).header("If-Modified-Since", Mutils.toHttpDate(oneSecBeforeLastModified)))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.header("last-modified"), is(lastModified));
                resp.body().bytes();
            }
        }
    }

    @Test
    public void contextsCanBeUsed() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(context("/a")
                .addHandler(context("/b")
                    .addHandler(classpathHandler("/sample-static"))
                    .addHandler(context("/c")
                        .addHandler(classpathHandler("/sample-static")
                            .withPathToServeFrom("/d")
                        ))))
            .start();

        Map<String, List<String>> headersFromGET;
        URL url = server.httpsUri().resolve("/a/b/c/d/index.html").toURL();
        try (Response resp = call(request().get().url(url))) {
            headersFromGET = resp.headers().toMultimap();
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
        try (Response resp = call(request().head().url(url))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers().toMultimap(), equalTo(headersFromGET));
            assertThat(resp.body().contentLength(), is(0L));
        }
        try (Response resp = call(request(server.uri().resolve("/a/b/")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }

        assertNotFound("/d/index.html");
    }

    @Test
    public void requestsWithDotDotOrTildesResultIn404s() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(fileOrClasspath("src/test/resources/does-not-exist", "/sample-static"))
            .start();

        assertNotFound("/../something.txt");
        assertNotFound("/images/../../something.txt");
        assertNotFound("/images/../blah");
    }

    @Test
    public void directoriesResultIn302s() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(context("/classpath").addHandler(classpathHandler("/sample-static")))
            .addHandler(context("/file").addHandler(fileHandler("src/test/resources/sample-static")))
            .start();

        String encodedDir = urlEncode("a, tricky - dir Name");
        try (Response resp = call(request(server.uri().resolve("/classpath/" + encodedDir)))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/classpath/" + encodedDir + "/").toString()));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
        try (Response resp = call(request(server.uri().resolve("/file/" + encodedDir)))) {
            assertThat(resp.code(), equalTo(302));
            assertThat(resp.header("location"), equalTo(server.uri().resolve("/file/" + encodedDir + "/").toString()));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @Test
    public void callsToContextNamesWithoutTrailingSlashesResultIn302() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("my-app")
                .addHandler(classpathHandler("/sample-static"))
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
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(context("/classpath").addHandler(classpathHandler("/sample-static")))
            .addHandler(context("/file").addHandler(fileHandler("src/test/resources/sample-static")))
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
        server = ServerUtils.httpsServerForTest()
            .addHandler(fileHandler("src/test/resources/sample-static")
                .withPathToServeFrom("/blah")
                .build())
            .start();

        try (Response badOne = call(request().url(server.httpsUri().resolve("/index.html").toURL()))) {
            assertThat(badOne.code(), is(404));
        }

        try (Response resp = call(request().url(server.httpsUri().resolve("/blah/index.html").toURL()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
    }

    @Test
    public void itCanDefaultToFilesSuchAsIndexHtml() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .addHandler(
                context("/blah").addHandler(
                    fileHandler("src/test/resources/sample-static")
                        .withDefaultFile("index.html"))
            )
            .start();

        try (Response resp = call(request().url(server.httpsUri().resolve("/blah/").toURL()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), is(readResource("/sample-static/index.html")));
        }
    }

    @Test
    public void contentTypesAreCorrect() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withGzip(1200, gzippableMimeTypes(getResourceTypes()))
            .addHandler(fileHandler("src/test/resources/sample-static"))
            .start();

//        assertContentTypeAndContent("/index.html", "text/html", false); // not sure why it's chunked but not gzipped. Probably just too small.
        assertContentTypeAndContent("/overview.txt", "text/plain;charset=utf-8", true);
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
            assertThat(resp.header("Vary"), is(expectGzip ? equalTo("accept-encoding") : nullValue()));
            assertThat(resp.body().string(), is(readResource("/sample-static" + urlDecode(relativePath))));

            if (expectGzip) {

                if (ClientUtils.isHttp2(resp)) {
                    assertThat(resp.headers().toString(), resp.header("Transfer-Encoding"), is(nullValue()));
                } else {
                    assertThat(resp.headers().toString(), resp.header("Transfer-Encoding"), is("chunked"));
                }
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
                headersFromHEAD.remove("content-length");
                headersFromHEAD.remove("content-encoding");
                headersFromGET.remove("transfer-encoding");
                if (resp.protocol() != Protocol.HTTP_2) {
                    headersFromHEAD.remove("vary");
                    headersFromGET.remove("vary");
                }
                assertThat(headersFromHEAD, equalTo(headersFromGET));
            } else {
                assertThat(headersFromHEAD, equalTo(headersFromGET));
            }

            if (!ClientUtils.isHttp2(resp)) {
                // TODO: this is broken on okhttpclient until https://github.com/square/okhttp/issues/4948 is fixed
                assertThat(resp.body().contentLength(), is(0L));
            }

        }

    }

    @Test
    public void directoryListingIsPossible() throws IOException {
        ResourceHandlerBuilder[] resourceHandlerBuilders = {
            fileHandler("src/test/resources/sample-static"), classpathHandler("/sample-static")};
        for (ResourceHandlerBuilder resourceHandlerBuilder : resourceHandlerBuilders) {
            server = ServerUtils.httpsServerForTest()
                .addHandler(context("umm")
                    .addHandler(resourceHandlerBuilder
                        .withDefaultFile(null)
                        .withDirectoryListing(true)
                    )
                )
                .start();
            try (Response resp = call(request(server.uri().resolve("/umm")))) {
                assertThat(resp.code(), is(302));
            }
            try (Response resp = call(request(server.uri().resolve("/umm/")))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.header("content-type"), is("text/html;charset=utf-8"));
                String html = resp.body().string();
                assertThat(html, not(containsString("Parent directory")));
                assertThat(html, containsString("<a href=\"alphanumerics.txt\">alphanumerics.txt</a>"));
                assertThat(html, containsString("<a href=\"a%2C%20tricky%20-%20dir%20Name&#x2F;\">a, tricky - dir Name&#x2F;</a>"));
            }
            String encodedDir = urlEncode("a, tricky - dir Name");
            try (Response resp = call(request(server.uri().resolve("/umm/" + encodedDir + "/")))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.header("content-type"), is("text/html;charset=utf-8"));
                String html = resp.body().string();
                assertThat(html, containsString("<h1>Index of &#x2F;umm&#x2F;a, tricky - dir Name&#x2F;</h1>"));
                assertThat(html, containsString("Parent directory"));
                assertThat(html, containsString("<a href=\"a%2C%20tricket%20-%20file%20name.txt\">a, tricket - file name.txt</a>"));
                assertThat(html, containsString("<a href=\"ooh%20ah&#x2F;\">ooh ah&#x2F;</a>"));
            }
        }
    }

    @Test
    public void directoryListingIsOffByDefault() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("umm")
                .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static"))
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/umm/images/")))) {
            assertThat(resp.code(), is(404));
        }
    }

    @Test
    public void defaultFileIsPreferredEvenWithDirectoryListing() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(context("umm")
                .addHandler(ResourceHandlerBuilder.fileHandler("src/test/resources/sample-static")
                    .withDirectoryListing(true)
                    .withDefaultFile("overview.txt")
                )
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/umm/")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), containsString("<title>Mu-Server API Documentation</title>"));
        }
    }

    @Test
    public void canServeResourcesFromMultipleJarsOnClasspath() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(context("/lib")
                .addHandler(context("/jquery")
                    .addHandler(classpathHandler("/META-INF/resources/webjars/jquery"))
                    .addHandler(context("/ui")
                        .addHandler(classpathHandler("/META-INF/resources/webjars/jquery-ui"))
                    )
                )
                .addHandler(context("/jquery-1.12.0")
                    .addHandler(classpathHandler("/META-INF/resources/webjars/jquery/1.12.0"))
                )
                .addHandler(context("/jquery-ui-1.12.1")
                    .addHandler(classpathHandler("/META-INF/resources/webjars/jquery-ui/1.12.1"))
                )
            )
            .start();

        try (Response resp = call(request(server.uri().resolve("/lib/jquery/1.12.0/jquery.min.js")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("application/javascript"));
            assertThat(resp.body().string(), is(readResource("/META-INF/resources/webjars/jquery/1.12.0/jquery.min.js")));
        }
        try (Response resp = call(request(server.uri().resolve("/lib/jquery/ui/1.12.1/jquery-ui.min.js")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("application/javascript"));
            assertThat(resp.body().string(), is(readResource("/META-INF/resources/webjars/jquery-ui/1.12.1/jquery-ui.min.js")));
        }
        try (Response resp = call(request(server.uri().resolve("/lib/jquery-1.12.0/jquery.min.js")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("application/javascript"));
            assertThat(resp.body().string(), is(readResource("/META-INF/resources/webjars/jquery/1.12.0/jquery.min.js")));
        }
        try (Response resp = call(request(server.uri().resolve("/lib/jquery-ui-1.12.1/jquery-ui.min.js")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Type"), is("application/javascript"));
            assertThat(resp.body().string(), is(readResource("/META-INF/resources/webjars/jquery-ui/1.12.1/jquery-ui.min.js")));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}