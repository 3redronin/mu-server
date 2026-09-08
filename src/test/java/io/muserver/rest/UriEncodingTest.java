package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.core.UriBuilder;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class UriEncodingTest {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void completeUtf8SequencesAreDecodedOnce() {
        assertThat(Jaxutils.leniantUrlDecode("caf%C3%A9/%e4%b8%ad/%F0%9F%98%80/%252F/+/%2G/%"),
            equalTo("café/中/😀/%2F/+/%2G/%"));
    }

    @Test
    public void encodedUnicodeCanBeImportedAndUsedInComponentsAndTemplates() {
        for (String encoded : new String[]{"caf%C3%A9", "caf%c3%a9", "%E4%B8%AD", "%F0%9F%98%80"}) {
            String decoded = Jaxutils.uriDecode(encoded);
            assertThat(UriBuilder.fromUri(URI.create("http://example.test/" + encoded)).build().getPath(),
                equalTo("/" + decoded));
            URI uri = UriBuilder.fromPath(encoded).segment(encoded).matrixParam("name", encoded)
                .queryParam("q", encoded).fragment(encoded).build();
            assertThat(uri.getPath(), equalTo(decoded + "/" + decoded + ";name=" + decoded));
            assertThat(uri.getQuery(), equalTo("q=" + decoded));
            assertThat(uri.getFragment(), equalTo(decoded));
            assertThat(UriBuilder.fromPath("{value}").buildFromEncoded(encoded).getPath(), equalTo(decoded));
            assertThat(UriBuilder.fromPath("{value}").resolveTemplateFromEncoded("value", encoded).build().getPath(),
                equalTo(decoded));
        }
    }

    @Test
    public void importingAUriPreservesComponentBoundariesAndEncodedPercentSigns() {
        URI original = URI.create("http://user%2520:password@example.test/a%2Fb%3Bc;k%3Dy=v%3Bw%2Cx/%252F?q=%252F%26x%3Dy#%252F");
        assertThat(UriBuilder.fromUri(original).build(), equalTo(original));
        assertThat(UriBuilder.fromPath("a%2Fb%3Bc;k%3Dy=v%3Bw%2Cx").build().toString(),
            equalTo("a%2Fb%3Bc;k%3Dy=v%3Bw%2Cx"));
        assertThat(UriBuilder.fromPath("{value}").buildFromEncoded("%252F").getRawPath(), equalTo("%252F"));
        assertThat(UriBuilder.fromPath("{value}").build("%2F").getRawPath(), equalTo("%252F"));
        assertThat(UriBuilder.fromPath("100%/%2G").build().getRawPath(), equalTo("100%25/%252G"));
    }

    @Test
    public void encodedUnicodeRouteLiteralsCanBeMatched() {
        assertThat(UriPattern.uriTemplateToRegex("/caf%C3%A9").matcher("/caf%C3%A9").prefixMatches(), equalTo(true));
    }

    @Test
    public void malformedRequestEncodingIsAClientErrorWithDefaultMapper() throws Exception {
        checkMalformedRequestEncoding(true);
    }

    @Test
    public void malformedRequestEncodingIsAClientErrorWithoutDefaultMapper() throws Exception {
        checkMalformedRequestEncoding(false);
    }

    private static void checkMalformedRequestEncoding(boolean defaultMapper) throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        @Path("value")
        class Resource {
            @GET
            @Path("{value}")
            public String value(@PathParam("value") String value, @MatrixParam("m") String matrix) {
                invocations.incrementAndGet();
                return value + ":" + matrix;
            }

            @GET
            @Path("application-error")
            public String applicationError() {
                throw new IllegalArgumentException("application error");
            }
        }
        RestHandlerBuilder handler = restHandler(new Resource());
        if (!defaultMapper) handler.removeExceptionMapper(Throwable.class);
        // A broad request-boundary catch must not hijack application exception mappers.
        handler.addExceptionMapper(IllegalArgumentException.class,
            e -> jakarta.ws.rs.core.Response.status(409).entity("application mapper").build());
        MuServer server = httpsServerForTest().addHandler(handler).start();
        try {
            for (String path : new String[]{"%FF", "%C3", "%C3%28", "good;m=%FF", "good;%FF=value"}) {
                try (Response response = call(request(server.uri().resolve("/value/" + path)))) {
                    assertThat(path, response.code(), equalTo(400));
                }
            }
            assertThat(invocations.get(), equalTo(0));
            try (Response response = call(request(server.uri().resolve("/value/caf%C3%A9+;m=%252F")))) {
                assertThat(response.code(), equalTo(200));
                assertThat(response.body().string(), equalTo("café+:%2F"));
            }
            try (Response response = call(request(server.uri().resolve("/value/application-error")))) {
                assertThat(response.code(), equalTo(409));
                assertThat(response.body().string(), equalTo("application mapper"));
            }
        } finally {
            server.stop();
        }
    }
}
