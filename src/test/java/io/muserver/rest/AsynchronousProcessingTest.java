package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.StringUtils;

import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.ConnectionCallback;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.assertNotTimedOut;

public class AsynchronousProcessingTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private MuServer server;

    @Test
    public void canUseTheSuspendedAnnotationToGetAnAsyncResponseObject() throws Exception {
        AsyncResponse[] captured = new AsyncResponse[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    captured[0] = ar;
                    MuAssert.sleep(100);
                    javax.ws.rs.core.Response resp = javax.ws.rs.core.Response.status(202).entity("Suspended/cancelled/done: " + ar.isSuspended() + ar.isCancelled() + ar.isDone()).build();
                    ar.resume(resp);
                });
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(202));
            assertThat(resp.body().string(), equalTo("Suspended/cancelled/done: truefalsefalse"));
        }
        assertThat(captured[0].isSuspended(), is(false));
        assertThat(captured[0].isCancelled(), is(false));
        assertThat(captured[0].isDone(), is(true));
    }


    @Test
    public void ifResumedWithExceptionThenItIsHandledNormally() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                executor.submit(() -> {
                    ar.resume(new BadRequestException("Bad bad bad request"));
                });
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), equalTo("400 Bad Request - Bad bad bad request"));
        }
    }


    @Test
    public void timeoutsCanBeSent() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch afterSentLatch = new CountDownLatch(1);
        Object[] afterTimeoutResult = new Object[1];
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
                assertNotTimedOut("Waiting until response finished", latch);
                afterTimeoutResult[0] = ar.resume("Hello");
                afterSentLatch.countDown();
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.body().string(), equalTo("503 Service Unavailable - HTTP 503 Service Unavailable"));
        }
        latch.countDown();
        assertNotTimedOut("Waiting until resume sent", afterSentLatch);
        assertThat(afterTimeoutResult[0], is(false));
    }


    @Test
    public void aCustomTimeoutHandlerCanBeUsed() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar) {
                ar.setTimeoutHandler(asyncResponse -> asyncResponse.resume("Oops, this is Hawkward"));
                ar.setTimeout(10, TimeUnit.MILLISECONDS);
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Oops, this is Hawkward"));
        }
    }


    @Test
    public void responsesCanBeCancelledWhichSendsA503() throws Exception {
        AtomicBoolean cancelResult = new AtomicBoolean();
        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar, @QueryParam("retryDate") Long retryDate, @QueryParam("retrySeconds") Integer retrySeconds) {
                executor.submit(() -> {
                    if (retrySeconds != null) {
                        cancelResult.set(ar.cancel(retrySeconds));
                    } else if (retryDate != null) {
                        cancelResult.set(ar.cancel(new Date(retryDate)));
                    } else {
                        cancelResult.set(ar.cancel());
                    }
                });
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is(nullValue()));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retrySeconds=123").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("123"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?retryDate=1530959539077").toString()))) {
            assertThat(resp.code(), is(503));
            assertThat(resp.header("Retry-After"), is("Sat, 7 Jul 2018 10:32:19 GMT"));
        }
    }


    @Test
    @Ignore("Not yet working")
    public void completionCallbacksCanBeRegistered() throws Exception {
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<Collection<Class<?>>> registered = new AtomicReference<>();

        @Path("samples")
        class Sample {
            @GET
            public void go(@Suspended AsyncResponse ar, @QueryParam("retryDate") Long retryDate, @QueryParam("retrySeconds") Integer retrySeconds) {
                registered.set(ar.register((ConnectionCallback) disconnected -> disconnectedLatch.countDown()));
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        OkHttpClient impatientClient = ClientUtils.client.newBuilder().readTimeout(1, TimeUnit.MILLISECONDS).build();
        try (Response ignored = impatientClient.newCall(request().url(server.uri().resolve("/samples").toString()).build()).execute()) {
            Assert.fail("This test expected a client timeout");
        } catch (SocketTimeoutException te) {
            assertNotTimedOut("Timed out waiting for disconnect timeout", disconnectedLatch);
        }

        assertThat(registered.get(), contains(ConnectionCallback.class));
    }

    @Test
    public void ifExceptionThrownAfterAsyncStartedButBeforeAsyncInvokedThenSomethingHappens() throws IOException {
        AtomicBoolean methodCalled = new AtomicBoolean(false);
        @Path("samples")
        class Sample {
            @POST
            public void echo(@Suspended AsyncResponse ar, byte[] input) {
                methodCalled.set(true);
            }
        }
        this.server = httpServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request()
            .post(RequestBody.create(MediaType.parse("text/plain"), ""))
            .url(server.uri().resolve("/samples").toString())
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("400 Bad Request"));
        }
        assertThat("Invalid request, but method was called", methodCalled.get(), is(false));
    }

    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }

}
