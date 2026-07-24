package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.*;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.*;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class JaxRSResponseTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }
    private MuServer server;

    @Test
    public void reportsWhetherItIsClosed() {
        Response response = JaxRSResponse.ok("hello").build();

        assertThat(response.isClosed(), is(false));
        response.close();
        assertThat(response.isClosed(), is(true));
    }

    @Test
    public void relativeLocationsAreResolvedAgainstTheApplicationBaseUri() {
        @Path("/resource")
        class ResourceWithRelativeLocation {
            @GET
            @Path("/created")
            public Response created() {
                return Response.created(URI.create("created")).status(200).build();
            }
        }

        RestHandler handler = restHandler(new ResourceWithRelativeLocation()).build();
        server = ServerUtils.httpsServerForTest()
            .addHandler(handler)
            .addHandler(io.muserver.ContextHandlerBuilder.context("/app")
                .addHandler(handler))
            .addHandler(io.muserver.ContextHandlerBuilder.context("/outer")
                .addHandler(io.muserver.ContextHandlerBuilder.context("/inner")
                    .addHandler(handler)))
            .start();

        assertRelativeLocation("/resource/created", "/created");
        assertRelativeLocation("/app/resource/created", "/app/created");
        assertRelativeLocation("/outer/inner/resource/created", "/outer/inner/created");
    }

    private void assertRelativeLocation(String requestPath, String expectedLocationPath) {
        try (okhttp3.Response response = call(request(server.uri().resolve(requestPath)))) {
            assertThat(response.header(HttpHeaders.LOCATION), is(server.uri().resolve(expectedLocationPath).toString()));
        }
    }

    @Test
    public void applicationDateReplacesTheDefaultResponseDate() {
        Date date = Mutils.fromHttpDate("Mon, 1 Jan 2018 02:23:20 GMT");
        @Path("/date")
        class DateResource {
            @GET
            public Response get() {
                return Response.ok().header(HttpHeaders.DATE, date).build();
            }
        }

        server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new DateResource()))
            .start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/date")))) {
            assertThat(response.headers(HttpHeaders.DATE), contains("Mon, 1 Jan 2018 02:23:20 GMT"));
        }
    }

    @Test
    public void headersCanBeGottenFromIt() {
        Response.ResponseBuilder builder = new JaxRSResponse.Builder()
            .allow("GET", "HEAD")
            .cacheControl(cacheControl())
            .contentLocation(URI.create("http://localhost:8080"))
            .cookie(new NewCookie("Some", "Value", "/here", "localhost", "no comment", 10, true))
            .encoding("UTF-8")
            .expires(new Date(1514773452217L))
            .language(Locale.CANADA_FRENCH)
            .lastModified(new Date(1514773400000L))
            .link(URI.create("http://www.example.org"), "meta")
            .location(URI.create("/some-location"))
            .status(201)
            .tag(new EntityTag("lkajsd\"fkljsklfdj", true))
            .header("X-Another", "something");

        JaxRSResponse response = (JaxRSResponse) builder.build();
        assertThat(response.getStatus(), is(201));

        MultivaluedMap<String, String> actual = response.getStringHeaders();
        assertThat(actual.get("allow"), contains("HEAD,GET"));
        assertThat(response.getAllowedMethods(), containsInAnyOrder("HEAD", "GET"));
        assertThat(actual.get("cache-control"), contains("private, no-transform, must-revalidate, max-age=10"));
        assertThat(response.getLength(), is(-1));
        assertThat(actual.get("content-location"), contains("http://localhost:8080"));
        assertThat(actual.get("content-encoding").toString(), actual.get("content-encoding"), contains("UTF-8"));
        assertThat(actual.get("expires"), contains("Mon, 1 Jan 2018 02:24:12 GMT"));
        assertThat(actual.get("content-language"), contains("fr-CA"));
        assertThat(response.getLanguage(), equalTo(Locale.CANADA_FRENCH));
        assertThat(actual.get("last-modified"), contains("Mon, 1 Jan 2018 02:23:20 GMT"));
        assertThat(response.getLastModified(), equalTo(Mutils.fromHttpDate("Mon, 1 Jan 2018 02:23:20 GMT")));

        assertThat(actual.get("link"), contains("<http://www.example.org>; rel=\"meta\""));
        assertThat(response.hasLink("meta"), is(true));
        assertThat(response.hasLink("beta"), is(false));
        assertThat(response.getLink("meta"), equalTo(Link.valueOf("<http://www.example.org>; rel=\"meta\"")));
        assertThat(response.getLink("beta"), is(nullValue()));
        assertThat(response.getLinks(), containsInAnyOrder(Link.valueOf("<http://www.example.org>; rel=\"meta\"")));
        assertThat(response.getLinkBuilder("meta").title("I-built-this").build().toString(), equalTo("<http://www.example.org>; rel=\"meta\"; title=\"I-built-this\""));

        assertThat(actual.get("location"), contains("/some-location"));
        assertThat(response.getLocation(), equalTo(URI.create("/some-location")));
        assertThat(actual.get("etag"), contains("W/\"lkajsd\\\"fkljsklfdj\""));
        assertThat(response.getEntityTag().toString(), is("W/\"lkajsd\\\"fkljsklfdj\""));
//        assertThat(actual.get("Vary"), equalTo("???"));
        assertThat(actual.get("x-another"), contains("something"));
    }

    @Test
    public void complexResponsesWork() {
        @Path("/complex")
        class Blah {
            @GET
            public Response get() {
                return Response.ok()
                    .type(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                    .cacheControl(CacheControl.valueOf("max-age=1000,s-maxage=31536000,no-store"))
                    .contentLocation(URI.create("http://example.org"))
                    .encoding("UTF-8")
                    .expires(new Date(1549980698731L))
                    .language(Locale.SIMPLIFIED_CHINESE)
                    .lastModified(new Date(1549900698731L))
                    .link(URI.create("http://example.org/contact"), "contact")
                    .link(URI.create("http://example.org/terms"), "terms")
                    .links(Link.fromUri("/readme").rel("readme").build())
                    .tag(EntityTag.valueOf("W/\"WEAKTAG\""))
                    .allow("GET", "GET", "HEAD")
                    .cookie(new NewCookie("token", "SLDKFJKLEWJRIOEWURIOD289374", "/complex", null, null, 10000, true, true))
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .header("arb", "bitrary")
                    .build();
            }
        }

        MuServer server = ServerUtils.httpsServerForTest().addHandler(restHandler(new Blah())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/complex").toString()))) {
            assertThat(resp.code(), is(500));
            assertThat(resp.headers(HttpHeaders.CONTENT_TYPE), contains(equalTo("application/octet-stream")));
            assertThat(resp.headers(HttpHeaders.CACHE_CONTROL), contains(equalTo("no-store, max-age=1000, s-maxage=31536000")));
            assertThat(resp.headers(HttpHeaders.CONTENT_LOCATION), contains(equalTo("http://example.org")));
            assertThat(resp.headers(HttpHeaders.CONTENT_ENCODING), contains(equalTo("UTF-8")));
            assertThat(resp.headers(HttpHeaders.CONTENT_LANGUAGE), contains(equalTo("zh-CN")));
            assertThat(resp.headers(HttpHeaders.LAST_MODIFIED), contains(equalTo("Mon, 11 Feb 2019 15:58:18 GMT")));
            assertThat(resp.headers(HttpHeaders.EXPIRES), contains(equalTo("Tue, 12 Feb 2019 14:11:38 GMT")));
            assertThat(resp.headers("link"), containsInAnyOrder(
                equalTo("<http://example.org/contact>; rel=\"contact\""),
                equalTo("<http://example.org/terms>; rel=\"terms\""),
                equalTo("</readme>; rel=\"readme\"")
            ));
            assertThat(resp.headers("etag"), contains(equalTo("W/\"WEAKTAG\"")));
            assertThat(resp.headers("allow"), contains(equalTo("HEAD,GET")));
            assertThat(resp.headers("set-cookie"), contains(containsString("token=SLDKFJKLEWJRIOEWURIOD289374; Max-Age=10000; Expires=")));
            assertThat(resp.headers("set-cookie"), contains(containsString("Path=/complex; Secure; HTTPOnly")));
            assertThat(resp.headers("arb"), contains(equalTo("bitrary")));
        } finally {
            server.stop();
        }
    }

    @Test
    public void externalResponseCookiesAreWrittenOnce() {
        NewCookie cookie = new NewCookie("external", "yes");
        Response externalDelegate = Response.noContent().cookie(cookie).build();
        externalDelegate.getMetadata().add(HttpHeaders.SET_COOKIE, cookie);
        Response source = externalResponse(externalDelegate);
        JaxRSResponse wrapped = JaxRSResponse.from(source);
        java.util.List<String> writtenCookies = new java.util.ArrayList<>();
        io.muserver.Headers headers = (io.muserver.Headers) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{io.muserver.Headers.class},
            (proxy, method, args) -> {
                if (method.getName().equals("add")) {
                    if (args[0].toString().equalsIgnoreCase(HttpHeaders.SET_COOKIE)) {
                        Object values = args[1];
                        if (values instanceof Iterable) {
                            for (Object value : (Iterable<?>) values) writtenCookies.add(value.toString());
                        } else {
                            writtenCookies.add(values.toString());
                        }
                    }
                    return proxy;
                }
                throw new UnsupportedOperationException(method.toString());
            });
        io.muserver.MuResponse target = (io.muserver.MuResponse) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{io.muserver.MuResponse.class},
            (proxy, method, args) -> {
                if (method.getName().equals("headers")) return headers;
                throw new UnsupportedOperationException(method.toString());
            });

        MuRuntimeDelegate.writeResponseHeaders(URI.create("https://example.org/"), wrapped, target, true);

        assertThat(writtenCookies, hasSize(1));
        assertThat(writtenCookies.get(0), containsString("external=yes"));
    }

    private static Response externalResponse(Response delegate) {
        return new Response() {
            @Override public int getStatus() { return delegate.getStatus(); }
            @Override public StatusType getStatusInfo() { return delegate.getStatusInfo(); }
            @Override public Object getEntity() { return delegate.getEntity(); }
            @Override public <T> T readEntity(Class<T> entityType) { return delegate.readEntity(entityType); }
            @Override public <T> T readEntity(GenericType<T> entityType) { return delegate.readEntity(entityType); }
            @Override public <T> T readEntity(Class<T> entityType, Annotation[] annotations) { return delegate.readEntity(entityType, annotations); }
            @Override public <T> T readEntity(GenericType<T> entityType, Annotation[] annotations) { return delegate.readEntity(entityType, annotations); }
            @Override public boolean hasEntity() { return delegate.hasEntity(); }
            @Override public boolean bufferEntity() { return delegate.bufferEntity(); }
            @Override public void close() { delegate.close(); }
            @Override public MediaType getMediaType() { return delegate.getMediaType(); }
            @Override public Locale getLanguage() { return delegate.getLanguage(); }
            @Override public int getLength() { return delegate.getLength(); }
            @Override public java.util.Set<String> getAllowedMethods() { return delegate.getAllowedMethods(); }
            @Override public java.util.Map<String, NewCookie> getCookies() { return delegate.getCookies(); }
            @Override public EntityTag getEntityTag() { return delegate.getEntityTag(); }
            @Override public Date getDate() { return delegate.getDate(); }
            @Override public Date getLastModified() { return delegate.getLastModified(); }
            @Override public URI getLocation() { return delegate.getLocation(); }
            @Override public java.util.Set<Link> getLinks() { return delegate.getLinks(); }
            @Override public boolean hasLink(String relation) { return delegate.hasLink(relation); }
            @Override public Link getLink(String relation) { return delegate.getLink(relation); }
            @Override public Link.Builder getLinkBuilder(String relation) { return delegate.getLinkBuilder(relation); }
            @Override public MultivaluedMap<String, Object> getMetadata() { return delegate.getMetadata(); }
            @Override public MultivaluedMap<String, String> getStringHeaders() { return delegate.getStringHeaders(); }
            @Override public String getHeaderString(String name) { return delegate.getHeaderString(name); }
        };
    }

    @Test
    public void usesHeaderDelegatesIfAvailable() {
        NewCookie newCookie = new NewCookie("some-name", "some value", "/path", "example.org", "comment", 32, true, true);
        Response resp = JaxRSResponse.ok()
            .header("cache", cacheControl())
            .header("string-val", "A string val")
            .header("int-val", 1234)
            .header("set-cookie", newCookie)
            .header("some-date", new Date(1665326590510L))
            .build();
        assertThat(resp.getHeaderString("cache"), is("private, no-transform, must-revalidate, max-age=10"));
        assertThat(resp.getHeaderString("string-val"), is("A string val"));
        assertThat(resp.getHeaderString("int-val"), is("1234"));
        assertThat(resp.getHeaderString("set-cookie"), is(MuRuntimeDelegate.getInstance().createHeaderDelegate(NewCookie.class).toString(newCookie)));
        assertThat(resp.getHeaderString("set-cookie"), containsStringIgnoringCase("max-age=32;"));
        assertThat(resp.getHeaderString("some-date"), is("Sun, 9 Oct 2022 14:43:10 GMT"));
    }

    @Test
    public void getDateWorks() {
        assertThat(JaxRSResponse.ok().build().getDate(), is(nullValue()));
        Date now = new Date();
        assertThat(JaxRSResponse.ok().header("date", now).build().getDate(), is(now));
    }

    @Test
    public void responseBuilderUsesDefaultStatusBasedOnEntity() {
        assertThat(new JaxRSResponse.Builder().entity("entity").build().getStatus(), is(200));
        assertThat(new JaxRSResponse.Builder().entity(null).build().getStatus(), is(204));
    }

    @Test
    public void canBufferInputStreams() throws IOException {
        AtomicInteger closeCount = new AtomicInteger(0);
        try (InputStream inputStream = new ByteArrayInputStream("Hello world".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closeCount.incrementAndGet();
                super.close();
            }
        };
             Response resp = JaxRSResponse.ok(inputStream, "text/plain").build()) {
            assertThat(resp.bufferEntity(), equalTo(true));
            assertThat(resp.bufferEntity(), equalTo(true));
            assertThat(inputStream.available(), equalTo(0));
            assertThat(closeCount.get(), equalTo(1));

            assertThat(resp.readEntity(String.class), equalTo("Hello world"));
            assertThat(resp.readEntity(String.class), equalTo("Hello world"));
            assertThat(resp.readEntity(String.class, new Annotation[0]), equalTo("Hello world"));
            assertThat(resp.readEntity(new GenericType(String.class), new Annotation[0]), equalTo("Hello world"));
            assertThat(resp.readEntity(new GenericType(String.class)), equalTo("Hello world"));
            assertThat(resp.readEntity(new GenericType(String.class)), equalTo("Hello world"));
        }
    }

    @Test
    public void canReadResponseEntitiesWithBuiltinReaders() throws IOException {
        try (InputStream inputStream = new ByteArrayInputStream("Hello world".getBytes(StandardCharsets.UTF_8));
            Response resp = JaxRSResponse.ok(inputStream, "text/plain").build()) {
            String entity = resp.readEntity(String.class);
            assertThat(entity, equalTo("Hello world"));
            assertThat(resp.getEntity(), equalTo("Hello world"));
            try {
                resp.readEntity(String.class);
                Assert.fail("Should fail because it wasn't buffered before being read");
            } catch (IllegalStateException e) {
                // expected
            }
        }
    }

    @Test
    public void inputStreamsCanBeUsedAsEntities() throws IOException {
        @Path("/files")
        class FileResource {
            @GET
            @Produces("text/html;charset=utf-8")
            public Response get() throws FileNotFoundException {
                return Response.ok().entity(new FileInputStream("src/test/resources/sample-static/index.html")).build();
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new FileResource())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/files").toString()))) {
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), containsString("</html>"));
        }
    }

    @Test
    public void inputStreamsCanBeUsedAsEntitiesAndCanBeBufferedAllowingOriginalStreamToBeClosedEarly() throws IOException {
        @Path("/files")
        class FileResource {
            @GET
            @Produces("text/html;charset=utf-8")
            public Response getBuffered() throws IOException {
                Response response;
                try (FileInputStream fis = new FileInputStream("src/test/resources/sample-static/index.html")) {
                    response = Response.ok().entity(fis).build();
                    response.bufferEntity();
                }
                return response;
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new FileResource())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/files").toString()))) {
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), containsString("</html>"));
        }
    }

    @Test
    public void inputStreamsCanBeUsedAsEntitiesAndCanBeRead() throws IOException {
        @Path("/files")
        class FileResource {
            @GET
            public Response getBuffered() throws IOException {
                Response response;
                try (FileInputStream fis = new FileInputStream("src/test/resources/sample-static/index.html")) {
                    response = Response.ok().entity(fis)
                        .type("text/html;charset=utf-8") // needs to be here so that readEntity knows it is a text input stream
                        .build();
                    String html = response.readEntity(String.class);
                    if (!html.contains("</html")) throw new RuntimeException("No HTML! " + html);
                }
                return response;
            }
        }
        server = ServerUtils.httpsServerForTest().addHandler(restHandler(new FileResource())).start();
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/files").toString()))) {
            assertThat(resp.header("Content-Type"), is("text/html;charset=utf-8"));
            assertThat(resp.body().string(), containsString("</html>"));
        }
    }


    @Test
    public void nonInputStreamBufferingIsIgnored() throws IOException {
        try (Response resp = JaxRSResponse.ok("Hello world", "text/plain").build()) {
            assertThat(resp.bufferEntity(), equalTo(false));
            assertThat(resp.bufferEntity(), equalTo(false));
        }
    }

    private static CacheControl cacheControl() {
        CacheControl cc = new CacheControl();
        cc.setMustRevalidate(true);
        cc.setPrivate(true);
        cc.setMaxAge(10);
        return cc;
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
