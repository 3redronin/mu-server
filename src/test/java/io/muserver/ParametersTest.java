package io.muserver;

import okhttp3.FormBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ParametersTest {

	private MuServer server;

	@Test public void queryStringsCanBeGot() throws MalformedURLException {
		Object[] actual = new Object[4];
		server = MuServerBuilder.httpServer().addHandler((request, response) -> {
			actual[0] = request.query().get("value1");
			actual[1] = request.query().get("value2");
			actual[2] = request.query().get("unspecified");
			actual[3] = request.uri().getPath();
			return true;
		}).start();

        try (Response ignored = call(request().url(http("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think")))) {
        }
        assertThat(actual[0], equalTo("something"));
		assertThat(actual[1], equalTo("something else i think"));
		assertThat(actual[2], equalTo(""));
		assertThat(actual[3], equalTo("/something/here.html"));
	}

	@Test public void queryStringParametersCanAppearMultipleTimes() throws MalformedURLException {
		Object[] actual = new Object[3];
		server = MuServerBuilder.httpServer().addHandler((request, response) -> {
			actual[0] = request.query().getAll("value1");
			actual[1] = request.query().getAll("value2");
			actual[2] = request.query().getAll("unspecified");
			return true;
		}).start();

        try (Response ignored = call(request().url(http("/something/here.html?value1=something&value1=somethingAgain&value2=something%20else+i+think")))) {
        }
		assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
		assertThat(actual[1], equalTo(asList("something else i think")));
		assertThat(actual[2], equalTo(Collections.<String>emptyList()));
	}

    @Test public void formParametersCanBeGot() throws MalformedURLException {

        List<String> vals = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            vals.add("This is value " + i);
        }

        List<String> actual = new ArrayList<>();
        server = MuServerBuilder.httpServer().addHandler((request, response) -> {
            for (int i = 0; i < vals.size(); i++) {
                actual.add(request.form().get("theNameOfTheFormParameter_" + i));
            }
            return true;
        }).start();

        String str = "/something/here.html?value1=unrelated";
        FormBody.Builder formBuilder = new FormBody.Builder();
        for (int i = 0; i < vals.size(); i++) {
            formBuilder.add("theNameOfTheFormParameter_" + i, vals.get(i));
        }
        try (Response ignored = call(request()
            .url(http(str))
            .post(formBuilder.build())
        )) {
        }
        assertThat(actual, equalTo(vals));
    }

    @Test public void formParametersWithMultipleValuesCanBeGot() throws MalformedURLException {
        Object[] actual = new Object[3];
        server = MuServerBuilder.httpServer().addHandler((request, response) -> {
            actual[0] = request.form().getAll("value1");
            actual[1] = request.form().getAll("value2");
            actual[2] = request.form().getAll("unspecified");
            return true;
        }).start();

        try (Response ignored = call(request()
            .url(http("/something/here.html?value1=unrelated"))
            .post(new FormBody.Builder()
                .add("value1", "something")
                .add("value1", "somethingAgain")
                .add("value2", "something else i think")
                .build())
        )) {
        }
        assertThat(actual[0], equalTo(asList("something", "somethingAgain")));
        assertThat(actual[1], equalTo(asList("something else i think")));
        assertThat(actual[2], equalTo(Collections.<String>emptyList()));
    }

    @Test public void exceptionsThrownWhenTryingToReadBodyAfterReadingFormData() {
        Throwable[] actual = new Throwable[1];
        server = MuServerBuilder.httpServer().addHandler((request, response) -> {
            request.form().get("blah");
            try {
                request.readBodyAsString();
            } catch (Throwable t) {
                actual[0] = t;
            }
            return true;
        }).start();

        try (Response ignored = call(request()
            .url(server.httpUri().toString())
            .post(new FormBody.Builder()
                .add("blah", "something")
                .build())
        )) {
        }
        assertThat(actual[0], instanceOf(IllegalStateException.class));
        assertThat(actual[0].getMessage(), equalTo("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods."));
    }

	@After public void stopIt() {
        MuAssert.stopAndCheck(server);
	}

    URL http(String str) throws MalformedURLException {
        return server.httpUri().resolve(str).toURL();
    }
}
