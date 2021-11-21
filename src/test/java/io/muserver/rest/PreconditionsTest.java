package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import java.net.URI;
import java.util.Date;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class PreconditionsTest {
    private MuServer server;

    @Test
    public void ifModifiedSinceCanBeUsed() throws Exception {
        Date lastModified = new Date();
        @Path("samples")
        class Sample {
            @GET
            public javax.ws.rs.core.Response get(@Context Request jaxRequest) {
                javax.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(lastModified);
                if (resp == null) {
                    resp = javax.ws.rs.core.Response.ok("The content");
                }
                return resp.lastModified(lastModified).build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(Mutils.toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        Date oneSecondAfterLastModified = new Date(lastModified.toInstant().plusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .header(HeaderNames.IF_MODIFIED_SINCE.toString(), Mutils.toHttpDate(oneSecondAfterLastModified))
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("last-modified"), equalTo(Mutils.toHttpDate(lastModified)));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        try (Response resp = call(request(url).header(HeaderNames.IF_MODIFIED_SINCE.toString(), Mutils.toHttpDate(lastModified)))) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("last-modified"), equalTo(Mutils.toHttpDate(lastModified)));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        Date oneSecondBeforeLastModified = new Date(lastModified.toInstant().minusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .header(HeaderNames.IF_MODIFIED_SINCE.toString(), Mutils.toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("last-modified"), equalTo(Mutils.toHttpDate(lastModified)));
            assertThat(resp.body().string(), equalTo("The content"));
        }


    }

    @Test
    public void ifNoneMatchCanBeUsedForGets() throws Exception {
        String etag = "some-etag";
        @Path("samples")
        class Sample {
            @GET
            public javax.ws.rs.core.Response get(@Context Request jaxRequest) {
                javax.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(new EntityTag(etag));
                if (resp == null) {
                    resp = javax.ws.rs.core.Response.ok("The content").tag(etag);
                }
                return resp.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @Test
    public void ifNoneMatchCanBeUsedForPuts() throws Exception {
        String etag = "some-etag";
        @Path("samples")
        class Sample {
            @PUT
            @Consumes("*/*")
            public javax.ws.rs.core.Response put(@Context Request jaxRequest) {
                javax.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(new EntityTag(etag));
                if (resp == null) {
                    resp = javax.ws.rs.core.Response.ok("The content").tag(etag);
                }
                return resp.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url).put(somePutBody()))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url).put(somePutBody())
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
        try (Response resp = call(request(url).put(somePutBody())
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(412));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }
    }

    @NotNull
    private RequestBody somePutBody() {
        return RequestBody.create("blah", okhttp3.MediaType.get("text/plain"));
    }


    @Test
    public void eTagsAndLastModifiedCanBothBeChecked() throws Exception {
        String etag = "some-etag";
        Date lastModified = new Date();

        @Path("samples")
        class Sample {
            @GET
            public javax.ws.rs.core.Response get(@Context Request jaxRequest) {
                javax.ws.rs.core.Response.ResponseBuilder resp = jaxRequest.evaluatePreconditions(lastModified, new EntityTag(etag));
                if (resp == null) {
                    resp = javax.ws.rs.core.Response.ok("The content");
                }
                return resp.tag(etag).build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }

        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "W/\"67ab43\"")
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
        )) {
            assertThat(resp.code(), equalTo(304));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().contentLength(), equalTo(0L));
        }

        Date oneSecondBeforeLastModified = new Date(lastModified.toInstant().minusSeconds(1).toEpochMilli());
        try (Response resp = call(request(url)
            .addHeader(HeaderNames.IF_NONE_MATCH.toString(), "\"" + etag + "\"")
            .addHeader(HeaderNames.IF_MODIFIED_SINCE.toString(), Mutils.toHttpDate(oneSecondBeforeLastModified))
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("etag"), equalTo("\"" + etag + "\""));
            assertThat(resp.body().string(), equalTo("The content"));
        }
    }


    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}