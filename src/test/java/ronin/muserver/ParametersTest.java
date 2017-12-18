package ronin.muserver;

import org.junit.After;
import org.junit.Test;

import java.net.MalformedURLException;
import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static ronin.muserver.MuServerBuilder.muServer;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ParametersTest {

	private MuServer server;

	@Test
	public void queryStringsCanBeGot() throws MalformedURLException {
		Object[] actual = new Object[7];
		server = muServer().addHandler((request, response) -> {
			actual[0] = request.getParameter("value1");
			actual[1] = request.getParameter("value2");
			actual[2] = request.getParameter("unspecified");
			actual[3] = request.getPath();
			return true;
		}).start();

		call(request().url(server.uri().resolve("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think").toURL()));
		assertThat(actual[0], equalTo("something"));
		assertThat(actual[1], equalTo("something else i think"));
		assertThat(actual[2], equalTo(""));
		assertThat(actual[3], equalTo("/something/here.html"));
	}

	@Test
	public void queryStringParametersCanAppearMultipleTimes() throws MalformedURLException {
		Object[] actual = new Object[3];
		server = muServer().addHandler((request, response) -> {
			actual[0] = request.getParameters("value1");
			actual[1] = request.getParameters("value2");
			actual[2] = request.getParameters("unspecified");
			return true;
		}).start();

		call(request().url(server.uri().resolve("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think").toURL()));
		assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
		assertThat(actual[1], equalTo(asList("something else i think")));
		assertThat(actual[2], equalTo(Collections.<String>emptyList()));
	}

	@After
	public void stopIt() {
		if (server != null) {
			server.stop();
		}
	}

}
