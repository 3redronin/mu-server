package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.MuServerBuilder.httpServer;
import static java.util.Collections.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.core.IsEqual.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.StringUtils.randomAsciiStringOfLength;

public class HeadersTest {

	private MuServer server;

	@Test
    public void canGetAndSetThem() {
		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
					String something = request.headers().get("X-Something");
					response.headers().add("X-Response", something);
					return true;
				}).start();

		String randomValue = UUID.randomUUID().toString();

        try (Response resp = call(xSomethingHeader(randomValue))) {
            assertThat(resp.header("X-Response"), equalTo(randomValue));
        }
	}

	@Test
    public void aHandlerCanChangeTheHeadersOfASubsequentHandler() {
		String randomValue = UUID.randomUUID().toString();

		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
					request.headers().set("X-Something", randomValue);
					return false;
				})
				.addHandler((request, response) -> {
					String something = request.headers().get("X-Something");
					response.headers().add("X-Response", something);
					return true;
				})
				.start();


        try (Response resp = call(xSomethingHeader("OriginalValue"))) {
            assertThat(resp.header("X-Response"), equalTo(randomValue));
        }
	}

	@Test
    public void largeHeadersAreFineIfConfigured() {
		server = MuServerBuilder.httpServer()
				.withMaxHeadersSize(33000)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

		String bigString = randomAsciiStringOfLength(32000);
        try (Response resp = call(xSomethingHeader(bigString))) {
            assertThat(resp.header("X-Something"), equalTo(bigString));
        }
	}

	@Test
    public void urlsThatAreTooLongAreRejected() throws MalformedURLException {
		AtomicBoolean handlerHit = new AtomicBoolean(false);
		server = MuServerBuilder.httpServer()
				.withMaxUrlSize(30)
				.addHandler((request, response) -> {
					System.out.println("URI is " + request.uri());
					handlerHit.set(true);
					return true;
				}).start();

        try (Response resp = call(request().url(server.httpUri().resolve("/this-is-much-longer-than-that-value-allowed-by-the-config-above-i-think").toURL()))) {
            assertThat(resp.code(), is(414));
        }
		assertThat(handlerHit.get(), is(false));
	}

	@Test
    public void a431IsReturnedIfTheHeadersAreTooLarge() {
		server = MuServerBuilder.httpServer()
				.withMaxHeadersSize(1024)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

        try (Response resp = call(xSomethingHeader(randomAsciiStringOfLength(1025)))) {
            assertThat(resp.code(), is(431));
            assertThat(resp.header("X-Something"), is(nullValue()));
        }
	}

    @Test
    public void ifXForwardedHeadersAreSpecifiedThenRequestUriUsesThem() {
        URI[] actual = new URI[2];
        server = MuServerBuilder.httpServer()
            .withHttpPort(12752)
            .addHandler((request, response) -> {
                actual[0] = request.uri();
                actual[1] = request.serverURI();
                return true;
            }).start();

        try (Response ignored = call(request()
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "www.example.org")
            .header("X-Forwarded-Port", "443")
            .url(server.httpUri().resolve("/blah?query=value").toString()))) {
        }
        assertThat(actual[1].toString(), equalTo("http://localhost:12752/blah?query=value"));
        assertThat(actual[0].toString(), equalTo("https://www.example.org/blah?query=value"));
    }

    @Test
    public void ifMultipleXForwardedHeadersAreSpecifiedThenRequestUriUsesTheFirst() {
        URI[] actual = new URI[2];
        server = MuServerBuilder.httpServer()
            .withHttpPort(12753)
            .addHandler((request, response) -> {
                actual[0] = request.uri();
                actual[1] = request.serverURI();
                return true;
            }).start();

        try (Response ignored = call(request()
            .header("X-Forwarded-Proto", "https")
            .addHeader("X-Forwarded-Proto", "http")
            .header("X-Forwarded-Host", "www.example.org:12000")
            .addHeader("X-Forwarded-Host", "localhost:8192")
            .url(server.httpUri().resolve("/blah?query=value").toString()))) {
        }
        assertThat(actual[1].toString(), equalTo("http://localhost:12753/blah?query=value"));
        assertThat(actual[0].toString(), equalTo("https://www.example.org:12000/blah?query=value"));
    }

    @Test
    public void ifNoResponseDataThenContentLengthIsZero() {
        server = MuServerBuilder.httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                response.headers().add("X-Blah", "ha");
                // no response writing
                return true;
            }).start();


        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("X-Blah"), is("ha"));
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
        }
    }

    @Test public void ifOutputStreamUsedThenTransferEncodingIsChunked() {
        server = MuServerBuilder.httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                try (PrintWriter writer = response.writer()) {
                    writer.println("Why, hello there");
                }
                return true;
            }).start();

        try (Response resp = call(request().url(server.httpUri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is("chunked"));
        }
    }

    @Test public void aRequestHasXForwardHostHeaderDontThrowException() throws IOException {
        final String host = "mu-server-io:1234";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }

    @Test public void aRequestHasXForwardHostAndHasNoPortDontThrowException() throws IOException {
        final String host = "mu-server-io";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }

    @Test public void aRequestHasXForwardHostAndXForwardedPortDontThrowExceptionAndUserPort() throws IOException {
        final String host = "mu-server-io:9999";
        final String port = "8888";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).header(HeaderNames.X_FORWARDED_PORT.toString(), port).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host.substring(0, host.lastIndexOf(":") + 1)+port+"/"));
        }
    }


    @Test public void aRquestWithErrorXForwardHostHeaderDontThrowException() throws IOException {
        final String host = "mu-server-io<error>:1234";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo(server.uri().toString()+"/"));
        }
    }

    @Test public void aRequestHasIPv6XForwardHostHeaderDontThrowException() throws IOException {
        final String host = "[2001:0db8:85a3:08d3:1319:8a2e:0370:7344]:1234";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }

    @Test public void anIPv6XForwardHostHeaderHasNoPortDontThrowException() throws IOException {
        final String host = "[2001:0db8:85a3:08d3:1319:8a2e:0370:7344]";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }

    @Test public void anIPv4XForwardHostHeaderDontThrowException() throws IOException {
        final String host = "192.168.1.1:1234";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }

    @Test public void anIPv4XForwardHostHeaderHasNoPortDontThrowException() throws IOException {
        final String host = "192.168.1.1";
        server = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request().header(HeaderNames.X_FORWARDED_HOST.toString(), host).url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("http://"+host+"/"));
        }
    }


    @After public void stopIt() {
        MuAssert.stopAndCheck(server);
	}

	Request.Builder xSomethingHeader(String value) {
		return request().header("X-Something", value).url(server.httpUri().toString());
	}

	@Test
    public void acceptHeaderCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.accept(), equalTo(emptyList()));

        headers.set("Accept", "text/html,application/xhtml+xml,application/xml ; q=0.9,image/webp,*/*;q=0.8");
        assertThat(headers.accept(), contains(
            ph("text/html"),
            ph("application/xhtml+xml"),
            ph("application/xml", "q", "0.9"),
            ph("image/webp"),
            ph("*/*", "q",  "0.8")
        ));
    }

    @Test
    public void acceptCharsetHeaderCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.acceptCharset(), equalTo(emptyList()));

        headers.set("Accept-Charset", "iso-8859-5, unicode-1-1;q=0.8");
        assertThat(headers.acceptCharset(), contains(
            ph("iso-8859-5"),
            ph("unicode-1-1", "q", "0.8")
        ));
    }

    @Test
    public void acceptEncodingHeaderCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.acceptEncoding(), equalTo(emptyList()));

        headers.set("Accept-Encoding", "compress, gzip");
        assertThat(headers.acceptEncoding(), contains(
            ph("compress"),
            ph("gzip")
        ));

        headers.set("Accept-Encoding", "*");
        assertThat(headers.acceptEncoding(), contains(
            ph("*")
        ));

        headers.set("Accept-Encoding", "compress;q=0.5, gzip;q=1.0");
        assertThat(headers.acceptEncoding(), contains(
            ph("compress", "q", "0.5"),
            ph("gzip", "q", "1.0")
        ));

        headers.set("Accept-Encoding", "gzip;q=1.0, identity; q=0.5, *;q=0");
        assertThat(headers.acceptEncoding(), contains(
            ph("gzip", "q", "1.0"),
            ph("identity", "q", "0.5"),
            ph("*", "q", "0")
        ));
    }

    @Test
    public void acceptLanguageHeaderCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.acceptLanguage(), equalTo(emptyList()));

        headers.set("Accept-Language", "da, en-gb;q=0.8, en;q=0.7");
        assertThat(headers.acceptLanguage(), contains(
            ph("da"),
            ph("en-gb", "q", "0.8"),
            ph("en", "q", "0.7")
        ));
    }

    @Test
    public void cacheControlHeaderCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.cacheControl().parameters(), equalTo(emptyMap()));

        headers.set("Cache-Control", "max-age=60");
        assertThat(headers.cacheControl().parameters(), equalTo(singletonMap("max-age", "60")));
        headers.set("Cache-Control", "private, community=\"UCI\"");
        assertThat(headers.cacheControl().parameters().keySet(), contains("private", "community"));
        assertThat(headers.cacheControl().parameter("community"), equalTo("UCI"));
    }

    @Test
    public void contentTypeCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.contentType(), is(nullValue()));

        headers.set("Content-Type", "text/html; charset=ISO-8859-4");
        assertThat(headers.contentType(), equalTo(new MediaType("text", "html", "ISO-8859-4")));
    }

    @Test
    public void forwardedHeadersCanBeParsed() {
        Headers headers = new Headers();
        assertThat(headers.forwarded(), equalTo(emptyList()));

        headers.set("Forwarded", "for=192.0.2.43");
        assertThat(headers.forwarded(), contains(
            new ForwardedHeader(null, "192.0.2.43", null, null, null)
        ));

        headers.set("Forwarded", "for=192.0.2.43," +
            "      for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com");
        assertThat(headers.forwarded(), contains(
            new ForwardedHeader(null, "192.0.2.43", null, null, null),
            new ForwardedHeader("203.0.113.60", "198.51.100.17", "example.com", "http", null)
        ));
    }

    private static ParameterizedHeaderWithValue ph(String value) {
        return new ParameterizedHeaderWithValue(value, emptyMap());
    }

    private static ParameterizedHeaderWithValue ph(String value, String paramName, String paramValue) {
        return new ParameterizedHeaderWithValue(value, Collections.singletonMap(paramName, paramValue));
    }

}
