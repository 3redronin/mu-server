package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scaffolding.ServerUtils;

import java.util.concurrent.CountDownLatch;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
public class Http1ShutdownTest {

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void shutdownWithNoRequestsIsAllGood(String protocol) throws Exception {
        var server = ServerUtils.httpsServerForTest(protocol).start();
        server.close();
        assertThat(server.stats().activeConnections(), equalTo(0L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void whenGracefulShutdownOccursWithNoRequestsInProgressThingsWork(String protocol) throws Exception {

        try (var server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.sendChunk("Started...");
                response.sendChunk("...stopped");
            })
            .start();
             var resp = call(request(server.uri()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Started......stopped"));
            server.close();
            assertThat(server.stats().completedRequests(), equalTo(1L));
            assertThat(server.stats().completedConnections(), equalTo(1L));
            assertThat(server.stats().activeConnections(), equalTo(0L));
        }


    }



    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    public void whenGracefulShutdownOccursInProgressRequestsComplete(String protocol) throws Exception {

        var responseStartedLatch = new CountDownLatch(1);
        var shutdownInitiatedLatch = new CountDownLatch(1);
        try (var server = ServerUtils.httpsServerForTest(protocol)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.sendChunk("Started...");
                responseStartedLatch.countDown();
                System.out.println("Waiting for shutdown init");
                shutdownInitiatedLatch.await();
                System.out.println("Writing chunk 2");
                response.sendChunk("...stopped");
            })
            .start();
            var resp = call(request(server.uri()))) {

            responseStartedLatch.await();
            new Thread(server::close).start();
            Thread.sleep(20); // hmm
            shutdownInitiatedLatch.countDown();
            System.out.println("Shutdown initiated");

            assertThat(resp.body().string(), equalTo("Started......stopped"));
        }

    }

}
