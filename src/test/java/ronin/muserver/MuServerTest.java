package ronin.muserver;

import okhttp3.*;
import okio.BufferedSink;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static ronin.muserver.MuServerBuilder.muServer;

public class MuServerTest {

	private final OkHttpClient client = new OkHttpClient();

	@Test
	public void asyncHandlersSupported() throws IOException, InterruptedException {
		MuServer muServer = muServer()
				.withHttpConnection(12808)
				.withHandlers(new MuHandler() {
					@Override
					public MuAsyncHandler start(AsyncContext ctx) {
						return new MuAsyncHandler() {
							@Override
							public void onHeaders() throws Exception {
								System.out.println("Request starting");
								ctx.response.status(201);
							}

							@Override
							public void onRequestData(ByteBuffer buffer) throws Exception {
								String text = StandardCharsets.UTF_8.decode(buffer).toString();
								System.out.println("Got: " + text);
								ctx.response.write(text);
							}

							@Override
							public void onRequestComplete() {
								System.out.println("Request complete");
								ctx.complete();
							}
						};
					}
				})
				.start();

		StringBuffer expected = new StringBuffer();
		RequestBody requestBody = new RequestBody() {
			@Override public MediaType contentType() {
				return MediaType.parse("text/plain");
			}

			@Override public void writeTo(BufferedSink sink) throws IOException {
				write(sink, "Numbers\n");
				write(sink, "-------\n");
				for (int i = 2; i <= 997; i++) {
					write(sink, String.format(" * %s\n", i));
				}
			}

			private void write(BufferedSink sink, String s) throws IOException {
				expected.append(s);
				sink.writeUtf8(s);
			}

		};

		Response resp = client.newCall(new Request.Builder()
				.url("http://localhost:12808")
				.post(requestBody)
				.build()).execute();

		muServer.stop();

		assertThat(resp.code(), is(201));
		assertThat(resp.body().string(), equalTo(expected.toString()));
	}

}