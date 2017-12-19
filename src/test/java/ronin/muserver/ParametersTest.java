package ronin.muserver;

import okhttp3.FormBody;
import org.junit.After;
import org.junit.Test;

import java.net.MalformedURLException;
import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static ronin.muserver.MuServerBuilder.muServer;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ParametersTest {

	private MuServer server;

	@Test
	public void queryStringsCanBeGot() throws MalformedURLException {
		Object[] actual = new Object[4];
		server = muServer().addHandler((request, response) -> {
			actual[0] = request.parameter("value1");
			actual[1] = request.parameter("value2");
			actual[2] = request.parameter("unspecified");
			actual[3] = request.uri().getPath();
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
			actual[0] = request.parameters("value1");
			actual[1] = request.parameters("value2");
			actual[2] = request.parameters("unspecified");
			return true;
		}).start();

		call(request().url(server.uri().resolve("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think").toURL()));
		assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
		assertThat(actual[1], equalTo(asList("something else i think")));
		assertThat(actual[2], equalTo(Collections.<String>emptyList()));
	}

    @Test
    public void formParametersCanBeGot() throws MalformedURLException {
        Object[] actual = new Object[3];
        server = muServer().addHandler((request, response) -> {
            actual[0] = request.formValue("value1");
            actual[1] = request.formValue("value2");
            actual[2] = request.formValue("unspecified");
            return true;
        }).start();

        call(request()
            .url(server.uri().resolve("/something/here.html?value1=unrelated").toURL())
            .post(new FormBody.Builder()
                .add("value1", "something")
                .add("value1", "somethingAgain")
                .add("value2", "something else i think")
                .build())
        );
        assertThat(actual[0], equalTo("something"));
        assertThat(actual[1], equalTo("something else i think"));
        assertThat(actual[2], equalTo(""));
    }

    @Test
    public void formParametersWithMultipleValuesCanBeGot() throws MalformedURLException {
        Object[] actual = new Object[3];
        server = muServer().addHandler((request, response) -> {
            actual[0] = request.formValues("value1");
            actual[1] = request.formValues("value2");
            actual[2] = request.formValues("unspecified");
            return true;
        }).start();

        call(request()
            .url(server.uri().resolve("/something/here.html?value1=unrelated").toURL())
            .post(new FormBody.Builder()
                .add("value1", "something")
                .add("value1", "somethingAgain")
                .add("value2", "something else i think")
                .build())
        );
        assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
        assertThat(actual[1], equalTo(asList("something else i think")));
        assertThat(actual[2], equalTo(Collections.<String>emptyList()));
    }

    @Test
    public void exceptionsThrownWhenTryingToReadBodyAfterReadingFormData() {
        Throwable[] actual = new Throwable[1];
        server = muServer().addHandler((request, response) -> {
            request.formValue("blah");
            try {
                request.readBodyAsString();
            } catch (Throwable t) {
                actual[0] = t;
            }
            return true;
        }).start();

        call(request()
            .url(server.url())
            .post(new FormBody.Builder()
                .add("blah", "something")
                .build())
        );
        assertThat(actual[0], instanceOf(IllegalStateException.class));
        assertThat(actual[0].getMessage(), equalTo("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or getFormValue() methods."));
    }

	@After
	public void stopIt() {
		if (server != null) {
			server.stop();
		}
	}

}
