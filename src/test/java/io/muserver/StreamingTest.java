package io.muserver;

import okhttp3.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static io.muserver.MuServerBuilder.httpsServer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.*;

public class StreamingTest {

	private MuServer server;

	@Test public void textCanBeWrittenWithThePrintWriter() throws Exception {
        server = ServerUtils.httpsServerForTest()
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

	@Test
    public void flushingTheOutputStreamCausesDataToBeSentToClient() throws Exception {
	    CountDownLatch latch1 = new CountDownLatch(1);
	    CountDownLatch latch2 = new CountDownLatch(1);
	    CountDownLatch latch3 = new CountDownLatch(1);
	    CountDownLatch latch4 = new CountDownLatch(1);

        server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                OutputStream os = response.outputStream();
                os.write("0".getBytes(UTF_8));
                os.flush();
                os.write("1".getBytes(UTF_8));
                latch1.countDown();
                MuAssert.assertNotTimedOut("latch2", latch2);
                os.flush();
                latch3.countDown();
                MuAssert.assertNotTimedOut("latch4", latch4);
                return true;
            }).start();

        try (Response resp = call(request(server.uri()))) {
            StringBuilder sb = new StringBuilder();
            int read;
            byte[] buffer = new byte[8192];
            InputStream bs = resp.body().byteStream();
            MuAssert.assertNotTimedOut("latch1", latch1);
            while ((read = bs.read(buffer)) < 1) {
            }
            sb.append(new String(buffer, 0, read, UTF_8));
            assertThat("Expecting '0' is written and flushed; '1' is written but not flushed",
                sb.toString(), equalTo("0"));
            latch2.countDown();
            MuAssert.assertNotTimedOut("latch3", latch3);
            while ((read = bs.read(buffer)) < 1) {
            }
            sb.append(new String(buffer, 0, read, UTF_8));
            assertThat(sb.toString(), equalTo("01"));
            latch4.countDown();

        }

    }

    @Test public void zeroByteWriteIsIgnored() throws Exception {
        server = ServerUtils.httpsServerForTest()
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
        server = ServerUtils.httpsServerForTest()
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
        server = ServerUtils.httpsServerForTest()
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
        server = ServerUtils.httpsServerForTest()
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
        server = ServerUtils.httpsServerForTest()
				.addHandler((request, response) -> {
					actual.add(request.inputStream().isPresent() ? "Present" : "Not Present");
					actual.add("Request body: " + request.readBodyAsString());
					return true;
				}).start();

        try (Response ignored = call(request(server.uri()))) {
        }
		assertThat(actual, equalTo(asList("Not Present", "Request body: ")));
	}

	@Test public void chunkingWorksWithNoKeepalive() throws Exception {
	    server = httpsServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType("text/plain");
                response.sendChunk("This ");
                response.sendChunk("is ");
                response.sendChunk("a ");
                response.sendChunk("call");
            })
            .start();
        // This test passes in OkHttpClient 4.9 as it doesn't seem to care about a truncated chunked response
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("https");
        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);
        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(connector));
        client.start();
        try {
            ContentResponse get = client.newRequest(server.uri())
                .headers(httpFields -> httpFields.put(HttpHeader.CONNECTION, "close"))
                .send();
            assertThat(get.getStatus(), is(200));
            assertThat(get.getContentAsString(), is("This is a call"));
        } finally {
            client.stop();
        }
    }

	@After public void stopIt() {
        MuAssert.stopAndCheck(server);
	}
}
