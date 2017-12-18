package ronin.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static ronin.muserver.MuServerBuilder.muServer;
import static scaffolding.StringUtils.randomString;

public class HeadersTest {

	private final OkHttpClient client = new OkHttpClient();
	private MuServer server;

	@Test
	public void canGetAndSetThem() throws IOException {
		server = muServer()
				.addHandler((request, response) -> {
					String something = request.headers().get("X-Something");
					response.headers().add("X-Response", something);
					return true;
				}).start();

		String randomValue = UUID.randomUUID().toString();

		Response resp = client.newCall(new Request.Builder()
				.header("X-Something", randomValue)
				.url(server.url())
				.build()).execute();

		assertThat(resp.header("X-Response"), equalTo(randomValue));
	}

	@Test
	public void aHandlerCanChangeTheHeadersOfASubsequentHandler() throws IOException {
		String randomValue = UUID.randomUUID().toString();

		server = muServer()
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


		Response resp = client.newCall(new Request.Builder()
				.header("X-Something", "OriginalValue")
				.url(server.url())
				.build()).execute();

		assertThat(resp.header("X-Response"), equalTo(randomValue));
	}

	@Test
	public void largeHeadersAreFineIfConfigured() throws IOException {
		server = muServer()
				.withMaxHeadersSize(33000)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

		String randomValue = randomString(32000);
		Response resp = client.newCall(new Request.Builder()
				.header("X-Something", randomValue)
				.url(server.url())
				.build()).execute();
		assertThat(resp.header("X-Something"), equalTo(randomValue));
	}

	@Test
	public void urlsThatAreTooLongAreRejected() throws IOException {
		AtomicBoolean handlerHit = new AtomicBoolean(false);
		server = muServer()
				.withMaxUrlSize(30)
				.addHandler((request, response) -> {
					System.out.println("URI is " + request.uri());
					handlerHit.set(true);
					return true;
				}).start();

		Response resp = client.newCall(new Request.Builder()
				.url(server.uri().resolve("/this-is-much-longer-than-that-value-allowed-by-the-config-above-i-think").toURL())
				.build()).execute();
		assertThat(resp.code(), is(414));
		assertThat(handlerHit.get(), is(false));
	}

	@Test
	public void a431IsReturnedIfTheHeadersAreTooLarge() throws IOException {
		server = muServer()
				.withMaxHeadersSize(1024)
				.addHandler((request, response) -> {
					response.headers().add(request.headers());
					return true;
				}).start();

		String randomValue = randomString(1025);
		Response resp = client.newCall(new Request.Builder()
				.header("X-Something", randomValue)
				.url(server.url())
				.build()).execute();
		assertThat(resp.code(), is(431)); // TODO why isn't Netty complaining?
		assertThat(resp.header("X-Something"), is(nullValue()));
	}

	@After
	public void stopIt() {
		if (server != null) {
			server.stop();
		}
	}
}
