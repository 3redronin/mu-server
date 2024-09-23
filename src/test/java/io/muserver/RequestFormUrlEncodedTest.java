package io.muserver;

import okhttp3.FormBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.IOException;
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

public class RequestFormUrlEncodedTest {

    private MuServer server;

    @Test
    public void emptyListParametersResultInEmptyList() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                response.write("query: " + request.query().getAll("query").isEmpty() + " form: "
                    + request.form().getAll("noForm").isEmpty() + " / " + request.form().getAll("forma").size());
            })
            .start();
        try (Response resp = call(request(server.uri())
            .post(new FormBody.Builder()
                .add("forma", "")
                .build())
        )) {
            assertThat(resp.body().string(), equalTo("query: true form: true / 1"));
        }
    }

    @Test
    public void formParametersCanBeGot() throws MalformedURLException {

        List<String> vals = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            vals.add("This is value " + i);
        }

        List<String> actual = new ArrayList<>();
        server = ServerUtils.httpsServerForTest().addHandler((request, response) -> {
            for (int i = 0; i < vals.size(); i++) {
                actual.add(request.form().get("theNameOfTheFormParameter_" + i));
            }
            actual.add(request.form().get("does-not-exist"));
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
        vals.add(null);
        assertThat(actual, equalTo(vals));
    }

    @Test
    public void formParametersWithMultipleValuesCanBeGot() throws MalformedURLException {
        Object[] actual = new Object[3];
        server = ServerUtils.httpsServerForTest().addHandler((request, response) -> {
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

    @Test
    public void exceptionsThrownWhenTryingToReadBodyAfterReadingFormData() {
        Throwable[] actual = new Throwable[1];
        server = ServerUtils.httpsServerForTest().addHandler((request, response) -> {
            request.form().get("blah");
            try {
                request.readBodyAsString();
            } catch (Throwable t) {
                actual[0] = t;
            }
            return true;
        }).start();

        try (Response ignored = call(request()
            .url(server.uri().toString())
            .post(new FormBody.Builder()
                .add("blah", "something")
                .build())
        )) {
        }
        assertThat(actual[0], instanceOf(IllegalStateException.class));
        assertThat(actual[0].getMessage(), equalTo("The body of the request message cannot be read twice. This can happen when calling any 2 of inputStream(), readBodyAsString(), or form() methods."));
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    URL http(String str) throws MalformedURLException {
        return server.uri().resolve(str).toURL();
    }
}
