package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerTypeArgs;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.StringUtils.randomAsciiStringOfLength;

public class HeadersTest {

    private MuServer server;

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void canGetAndSetThem(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                String something = request.headers().get("X-Something");
                response.headers().add("X-Response", something);
                response.write("val: " + request.headers().get("not-on-request"));
                return true;
            }).start();

        String randomValue = UUID.randomUUID().toString();

        try (Response resp = call(xSomethingHeader(randomValue))) {
            assertThat(resp.header("X-Response"), equalTo(randomValue));
            assertThat(resp.body().string(), is("val: null"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void pseudoHeadersAreNotPresentInHeaders(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                for (Map.Entry<String, String> header : request.headers()) {
                    if (header.getKey().startsWith(":")) {
                        response.sendChunk(header + " ");
                    }
                }
                return true;
            }).start();

        String randomValue = UUID.randomUUID().toString();

        try (Response resp = call(xSomethingHeader(randomValue))) {
            assertThat(resp.body().string(), is(""));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aHandlerCanChangeTheHeadersOfASubsequentHandler(String protocol) {
        String randomValue = UUID.randomUUID().toString();

        server = ServerUtils.httpsServerForTest(protocol)
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

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void largeHeadersAreFineIfConfigured(String protocol) {
        server = ServerUtils.httpsServerForTest(protocol)
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

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void urlsThatAreTooLongAreRejected(String protocol) throws IOException {
        AtomicBoolean handlerHit = new AtomicBoolean(false);
        server = ServerUtils.httpsServerForTest(protocol)
            .withMaxUrlSize(30)
            .addHandler((request, response) -> {
                handlerHit.set(true);
                return true;
            }).start();

        try (Response resp = call(request(server.uri().resolve("/this-is-much-longer-than-that-value-allowed-by-the-config-above-i-think")))) {
            assertThat(resp.code(), is(414));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), is("414 URI Too Long"));
        }
        assertThat(handlerHit.get(), is(false));
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void a431IsReturnedIfTheHeadersAreTooLarge(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .withMaxHeadersSize(1024)
            .addHandler((request, response) -> {
                response.headers().add(request.headers());
                return true;
            }).start();

        try (Response resp = call(xSomethingHeader(randomAsciiStringOfLength(1025)))) {
            assertThat(resp.code(), is(431));
            assertThat(resp.body().string(), is("431 Request Header Fields Too Large"));
            assertThat(resp.header("X-Something"), is(nullValue()));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void largeHeadersCanBeConfigured(String protocol) throws Exception {
        String value = "a".repeat(26000);
        server = ServerUtils.httpsServerForTest(protocol)
            .withMaxHeadersSize(value.length() + 1000)
            .addHandler(Method.GET, "/", (req, resp, pp) -> {
                resp.headers().set("X-Large-Response", req.headers().get("x-large"));
                resp.write(req.headers().get("X-Large"));
            })
            .start();

        try (Response resp = call(request(server.uri()).header("x-Large", value))) {
            assertThat(resp.header("x-large-response"), equalTo(value));
            assertThat(resp.body().string(), equalTo(value));
        }

    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void ifXForwardedHeadersAreSpecifiedThenRequestUriUsesThem(String protocol) {
        URI[] actual = new URI[2];
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                actual[0] = request.uri();
                actual[1] = request.serverURI();
                return true;
            }).start();

        call(request(server.uri().resolve("/blah?query=value"))
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "www.example.org")
            .header("X-Forwarded-Port", "443")
        ).close();
        assertThat(actual[0].toString(), equalTo("https://www.example.org/blah?query=value"));
        assertThat(actual[1].toString(), equalTo(server.uri().getScheme() + "://localhost:" + server.uri().getPort() + "/blah?query=value"));
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void ifMultipleXForwardedHeadersAreSpecifiedThenRequestUriUsesTheFirst(String protocol) {
        AtomicReference<List<ForwardedHeader>> forwardedHeaders = new AtomicReference<>();
        URI[] actual = new URI[2];
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                actual[0] = request.uri();
                actual[1] = request.serverURI();
                forwardedHeaders.set(request.headers().forwarded());
                return true;
            }).start();

        call(request(server.uri().resolve("/blah?query=value"))
            .header("X-Forwarded-Proto", "https, ftp")
            .addHeader("X-Forwarded-Proto", "http")
            .header("X-Forwarded-Host", "www.example.org:12000, second.example.org")
            .addHeader("X-Forwarded-Host", "localhost:8192")
        ).close();
        assertThat(actual[1].toString(), equalTo(server.uri().getScheme() + "://localhost:" + server.uri().getPort() + "/blah?query=value"));
        assertThat(actual[0].toString(), equalTo("https://www.example.org:12000/blah?query=value"));
        assertThat(forwardedHeaders.get(), Matchers.contains(
            ForwardedHeader.fromString("host=\"www.example.org:12000\";proto=https").get(0),
            ForwardedHeader.fromString("host=second.example.org;proto=ftp").get(0),
            ForwardedHeader.fromString("host=\"localhost:8192\";proto=http").get(0)
        ));
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void ifNoResponseDataThenContentLengthIsZero(String protocol) {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                response.status(200);
                response.headers().add("X-Blah", "ha");
                // no response writing
                return true;
            }).start();


        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("X-Blah"), is("ha"));
            assertThat(resp.header("Content-Length"), is("0"));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void ifOutputStreamUsedThenTransferEncodingIsUnknown(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler((request, response) -> {
                response.status(200);
                try (PrintWriter writer = response.writer()) {
                    writer.print("Why, hello there");
                }
                return true;
            }).start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.body().string(), is("Why, hello there"));
            if (ClientUtils.isHttp2(resp)) {
                assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            } else {
                assertThat(resp.header("Transfer-Encoding"), is("chunked"));
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aRequestHasXForwardHostHeaderDontThrowException(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "mu-server-io:1234"))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://mu-server-io:1234/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aRequestHasXForwardHostAndHasNoPortDontThrowException(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "mu-server-io"))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://mu-server-io/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aRequestHasXForwardHostAndXForwardedPortDontThrowExceptionAndUsePort(String protocol) throws IOException {
        final String host = "mu-server-io:9999";
        final String port = "8888";
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), host)
            .header(HeaderNames.X_FORWARDED_PORT.toString(), port))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://mu-server-io:8888/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void forwardedHostsCanHaveColons(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                PrintWriter writer = response.writer();
                writer.print(ForwardedHeader.toString(request.headers().forwarded()));
                writer.print(" - " + request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_FOR.toString(), "10.10.0.10")
            .header(HeaderNames.X_FORWARDED_PROTO.toString(), "https")
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "host.example.org:8000")
            .header(HeaderNames.FORWARDED.toString(), "for=10.10.0.10;proto=https;host=host.example.org:8000;by=127.0.0.1")
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("by=127.0.0.1;for=10.10.0.10;host=\"host.example.org:8000\";proto=https - https://host.example.org:8000/"));
        }
    }


    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void clientIPUsesForwardedValueIfSpecified(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.write(request.clientIP());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_FOR.toString(), "10.10.0.10")
        )) {
            assertThat(resp.body().string(), equalTo("10.10.0.10"));
        }
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.FORWARDED.toString(), ForwardedHeader.fromString("by=10.10.0.12").get(0).toString()) // should be ignored because no 'for' value
            .addHeader(HeaderNames.FORWARDED.toString(), ForwardedHeader.fromString("for=10.10.0.11").get(0).toString())
        )) {
            assertThat(resp.body().string(), equalTo("10.10.0.11"));
        }
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("127.0.0.1"));
        }
    }


    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aRquestWithErrorXForwardHostHeaderDontThrowException(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "mu-server-io<error>:1234")
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo(server.uri().toString() + "/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void aRequestHasIPv6XForwardHostHeaderDontThrowException(String protocol) throws IOException {
        final String host = "[2001:0db8:85a3:08d3:1319:8a2e:0370:7344]:1234";
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), host)
        )) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://" + host + "/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void anIPv6XForwardHostHeaderHasNoPortDontThrowException(String protocol) throws IOException {
        final String host = "[2001:0db8:85a3:08d3:1319:8a2e:0370:7344]";
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), host))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://" + host + "/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void anIPv4XForwardHostHeaderDontThrowException(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "192.168.1.1:1234"))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://192.168.1.1:1234/"));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void anIPv4XForwardHostHeaderHasNoPortDontThrowException(String protocol) throws IOException {
        server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.status(200);
                response.writer().print(request.uri());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .header(HeaderNames.X_FORWARDED_HOST.toString(), "192.168.1.1"))) {
            assertThat(resp.code(), is(200));
            var scheme = protocol.equals("http") ? "http" : "https";
            assertThat(resp.body().string(), equalTo(scheme + "://192.168.1.1/"));
        }
    }

    Request.Builder xSomethingHeader(String value) {
        return request().header("X-Something", value).url(server.uri().toString());
    }

    @ParameterizedTest
    @ArgumentsSource(ServerTypeArgs.class)
    public void httpHeadersToStringDoesNotLogSensitiveHeaders(String protocol) {
        Headers headers = new Mu3Headers();
        headers.add("some-header", "value 1");
        headers.add("some-header", "value 2");
        headers.set(HeaderNames.AUTHORIZATION.toString().toUpperCase(), "shouldnotprint");
        headers.set(HeaderNames.SET_COOKIE, "shouldnotprint");
        headers.set(HeaderNames.COOKIE, "shouldnotprint 1");
        headers.add(HeaderNames.COOKIE.toString().toUpperCase(), "shouldnotprint 2");
        assertThat(headers.toString(), allOf(
            not(containsStringIgnoringCase("shouldnotprint")),
            containsStringIgnoringCase("some-header: value 1"),
            containsStringIgnoringCase("some-header: value 2"),
            containsStringIgnoringCase("cookie: (hidden)"),
            containsStringIgnoringCase("set-cookie: (hidden)"),
            containsStringIgnoringCase("authorization: (hidden)")
        ));
        assertThat(headers.toString(Collections.emptyList()), allOf(
            not(containsStringIgnoringCase("(hidden)")),
            containsStringIgnoringCase("some-header: value 1"),
            containsStringIgnoringCase("some-header: value 2"),
            containsStringIgnoringCase("cookie: shouldnotprint"),
            containsStringIgnoringCase("cookie: shouldnotprint 2"),
            containsStringIgnoringCase("set-cookie: shouldnotprint"),
            containsStringIgnoringCase("authorization: shouldnotprint")
        ));
        assertThat(headers.toString(Collections.singleton("SOME-header")), allOf(
            not(containsStringIgnoringCase("value 1")),
            not(containsStringIgnoringCase("value 2")),
            containsStringIgnoringCase("some-header: (hidden)"),
            containsStringIgnoringCase("some-header: (hidden)"),
            containsStringIgnoringCase("cookie: shouldnotprint"),
            containsStringIgnoringCase("cookie: shouldnotprint 2"),
            containsStringIgnoringCase("set-cookie: shouldnotprint"),
            containsStringIgnoringCase("authorization: shouldnotprint")
        ));
    }

}
