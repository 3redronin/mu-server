package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;

public class StreamingTest {

	private MuServer server;

	@Test public void textCanBeWrittenWithThePrintWriter() throws Exception {
		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
                    response.contentType(ContentTypes.TEXT_PLAIN);
					try (PrintWriter writer = response.writer()) {
						writer.println("Hello, world");
						writer.print("What's happening?");
					}
					return true;
				}).start();

        String actual;
        try (Response resp = call(request().url(server.httpUri().toString()))) {
            actual = resp.body().string();
        }
        assertThat(actual, equalTo(String.format("Hello, world%nWhat's happening?")));
	}

	@Test public void requestDataCanBeReadFromTheInputStream() throws Exception {
		server = MuServerBuilder.httpServer()
            .withGzipEnabled(false)
				.addHandler((request, response) -> {
					try (InputStream in = request.inputStream().get();
					     OutputStream out = response.outputStream()) {
						byte[] buffer = new byte[128];
						int read;
						while ((read = in.read(buffer)) > -1) {
							out.write(buffer, 0, read);
						}
						out.flush();
					}
					return true;
				}).start();

		StringBuffer sentData = new StringBuffer();
        try (Response resp = call(request()
            .url(server.httpUri().toString())
            .post(largeRequestBody(sentData)))) {
            String actual = new String(resp.body().bytes(), UTF_8);
            assertThat(actual, equalTo(sentData.toString()));
        }
	}

	@Test public void theWholeRequestBodyCanBeReadAsAStringWithABlockingCall() throws Exception {
		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
					response.write(request.readBodyAsString());
					return true;
				}).start();

		StringBuffer sentData = new StringBuffer();
        try (Response resp = call(request()
            .url(server.httpUri().toString())
            .post(largeRequestBody(sentData)))) {
            assertThat(resp.body().string(), equalTo(sentData.toString()));
        }
	}

	@Test public void thereIsNoInputStreamIfThereIsNoRequestBody() throws Exception {
		List<String> actual = new ArrayList<>();
		server = MuServerBuilder.httpServer()
				.addHandler((request, response) -> {
					actual.add(request.inputStream().isPresent() ? "Present" : "Not Present");
					actual.add("Request body: " + request.readBodyAsString());
					return true;
				}).start();

        try (Response ignored = call(request().url(server.httpUri().toString()))) {
        }
		assertThat(actual, equalTo(asList("Not Present", "Request body: ")));
	}


	@After public void stopIt() {
        MuAssert.stopAndCheck(server);
	}
}
