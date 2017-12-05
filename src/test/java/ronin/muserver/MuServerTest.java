package ronin.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static ronin.muserver.MuServerBuilder.muServer;

public class MuServerTest {

	private final OkHttpClient client = new OkHttpClient();

	@Test
	public void blah() throws IOException, InterruptedException {
		MuServer muServer = muServer().withHttpConnection(12808).build();
		muServer.start();

		Response resp = client.newCall(new Request.Builder()
				.url("http://localhost:12808")
				.build()).execute();

		muServer.stop();

		assertThat(resp.code(), is(200));
	}

}