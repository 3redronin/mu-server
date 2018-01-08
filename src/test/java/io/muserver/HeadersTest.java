package io.muserver;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.StringUtils.randomStringOfLength;

public class HeadersTest {

	private MuServer server;

	@Test public void canGetAndSetThem() {
		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
					String something = request.headers().get("X-Something");
					response.headers().add("X-Response", something);
					return true;
				}).start();

		String randomValue = UUID.randomUUID().toString();

		Response resp = call(xSomethingHeader(randomValue));

		assertThat(resp.header("X-Response"), equalTo(randomValue));
	}

	@Test public void aHandlerCanChangeTheHeadersOfASubsequentHandler() {
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


		Response resp = call(xSomethingHeader("OriginalValue"));
		assertThat(resp.header("X-Response"), equalTo(randomValue));
	}

	@Test public void largeHeadersAreFineIfConfigured() {
		server = MuServerBuilder.httpServer()
				.withMaxHeadersSize(33000)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

		String bigString = randomStringOfLength(32000);
		Response resp = call(xSomethingHeader(bigString));
		assertThat(resp.header("X-Something"), equalTo(bigString));
	}

	@Test public void urlsThatAreTooLongAreRejected() throws MalformedURLException {
		AtomicBoolean handlerHit = new AtomicBoolean(false);
		server = MuServerBuilder.httpServer()
				.withMaxUrlSize(30)
				.addHandler((request, response) -> {
					System.out.println("URI is " + request.uri());
					handlerHit.set(true);
					return true;
				}).start();

		Response resp = call(request().url(server.httpUri().resolve("/this-is-much-longer-than-that-value-allowed-by-the-config-above-i-think").toURL()));
		resp.close();
		assertThat(resp.code(), is(414));
		assertThat(handlerHit.get(), is(false));
	}

	@Test public void a431IsReturnedIfTheHeadersAreTooLarge() {
		server = MuServerBuilder.httpServer()
				.withMaxHeadersSize(1024)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

		Response resp = call(xSomethingHeader(randomStringOfLength(1025)));
		resp.close();
		assertThat(resp.code(), is(431));
		assertThat(resp.header("X-Something"), is(nullValue()));
	}

	@Test public void ifXForwardedHeadersAreSpecifiedThenRequestUriUsesThem() {
		URI[] actual = new URI[2];
		server = MuServerBuilder.httpServer()
				.withHttpConnection(12752)
				.withMaxHeadersSize(1024)
				.addHandler((request, response) -> {
					actual[0] = request.uri();
					actual[1] = request.serverURI();
					return true;
				}).start();

		call(request()
				.header("X-Forwarded-Proto", "https")
				.header("X-Forwarded-Host", "www.example.org")
				.header("X-Forwarded-Port", "443")
				.url(server.httpUri().resolve("/blah?query=value").toString()));
		assertThat(actual[1].toString(), equalTo("http://localhost:12752/blah?query=value"));
		assertThat(actual[0].toString(), equalTo("https://www.example.org/blah?query=value"));
	}

    @Test public void ifNoResponseDataThenContentLengthIsZero() {
        server = MuServerBuilder.httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                response.headers().add("X-Blah", "ha");
                // no response writing
                return true;
            }).start();


        Response resp = call(request().url(server.httpUrl()));
        assertThat(resp.code(), is(200));
        assertThat(resp.header("X-Blah"), is("ha"));
        assertThat(resp.header("Content-Length"), is("0"));
        assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
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

        Response resp = call(request().url(server.httpUrl()));
        resp.close();
        assertThat(resp.code(), is(200));
        assertThat(resp.header("Content-Length"), is(nullValue()));
        assertThat(resp.header("Transfer-Encoding"), is("chunked"));
    }


    @After public void stopIt() {
		server.stop();
	}

	Request.Builder xSomethingHeader(String value) {
		return request().header("X-Something", value).url(server.httpUrl());
	}
}
