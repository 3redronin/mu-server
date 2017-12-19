package ronin.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static ronin.muserver.MuServerBuilder.httpServer;
import static scaffolding.ClientUtils.*;

public class MuServerTest {

	private MuServer server;

	@Test
	public void portZeroCanBeUsed() throws Exception {
		server = httpServer().start();
		Response resp = call(request().url(server.url()));
		assertThat(resp.code(), is(404));
	}

	@Test
	public void syncHandlersSupported() throws IOException {
		List<String> handlersHit = new ArrayList<>();

		server = httpServer()
				.withHttpConnection(12808)
				.addHandler((request, response) -> {
					handlersHit.add("Logger");
					System.out.println("Got " + request);
					return false;
				})
				.addHandler(Method.GET, "/blah", (request, response) -> {
					handlersHit.add("BlahHandler");
					System.out.println("Running sync handler");
					response.status(202);
					response.write("This is a test");
					System.out.println("Sync handler complete");
					return true;
				})
				.addHandler((request, response) -> {
					handlersHit.add("LastHandler");
					return true;
				})
				.start();

		Response resp = call(request().url("http://localhost:12808/blah"));
		assertThat(resp.code(), is(202));
		assertThat(resp.body().string(), equalTo("This is a test"));
		assertThat(handlersHit, equalTo(asList("Logger", "BlahHandler")));
	}

	@Test
	public void asyncHandlersSupported() throws IOException {
		server = httpServer()
				.withHttpConnection(12808)
				.addAsyncHandler(new AsyncMuHandler() {
					public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
						System.out.println("I am a logging handler and saw " + ctx.request);
						return false;
					}

					public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
					}

					public void onRequestComplete(AsyncContext ctx) {
					}
				})
				.addAsyncHandler(new AsyncMuHandler() {
					@Override
					public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
						System.out.println("Request starting");
						ctx.response.status(201);
						return true;
					}

					@Override
					public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
						String text = StandardCharsets.UTF_8.decode(buffer).toString();
						System.out.println("Got: " + text);
						ctx.response.writeAsync(text);
					}

					@Override
					public void onRequestComplete(AsyncContext ctx) {
						System.out.println("Request complete");
						ctx.complete();
					}
				})
				.addAsyncHandler(new AsyncMuHandler() {
					public boolean onHeaders(AsyncContext ctx, Headers headers) throws Exception {
						throw new RuntimeException("This should never get here");
					}

					public void onRequestData(AsyncContext ctx, ByteBuffer buffer) throws Exception {
					}

					public void onRequestComplete(AsyncContext ctx) {
					}
				})
				.start();

		StringBuffer expected = new StringBuffer();

		Response resp = call(request()
				.url("http://localhost:12808")
				.post(largeRequestBody(expected))
		);

		assertThat(resp.code(), is(201));
		assertThat(resp.body().string(), equalTo(expected.toString()));
	}

	@After
	public void stopIt() {
		if (server != null) {
			server.stop();
		}
	}

}