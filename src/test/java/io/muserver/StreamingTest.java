package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
        try (Response resp = call(request(server.uri()))) {
            actual = resp.body().string();
        }
        assertThat(actual, equalTo(String.format("Hello, world%nWhat's happening?")));
	}

    @Test public void zeroByteWriteIsIgnored() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler((request, response) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                try (OutputStream out = response.outputStream()) {
                    out.write(new byte[0]);
                    out.flush();
                    out.write("Hi".getBytes(UTF_8));
                }
                return true;
            }).start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hi"));
        }
    }

    @Test public void bufferedOutputWritersAreOkay() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler((request, response) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                try (BufferedOutputStream writer = new BufferedOutputStream(response.outputStream(), 8192)) {
                    writer.write("Hi".getBytes(UTF_8));
                }
                return true;
            }).start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hi"));
        }
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
        try (Response resp = call(request(server.uri())
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
        try (Response resp = call(request(server.uri())
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

        try (Response ignored = call(request(server.uri()))) {
        }
		assertThat(actual, equalTo(asList("Not Present", "Request body: ")));
	}


	@After public void stopIt() {
        MuAssert.stopAndCheck(server);
	}
}
