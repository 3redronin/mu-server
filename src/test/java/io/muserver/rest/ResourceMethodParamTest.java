package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.FormBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ResourceMethodParamTest {


    public static final List<ParamConverterProvider> BUILT_IN_PARAM_PROVIDERS = Collections.singletonList(new BuiltInParamConverterProvider());
    private MuServer server;

    @Test
    public void canFindStuffOut() {

        @SuppressWarnings("unused")
        class Sample {
            public void defaultAndEncoded(@QueryParam("dummy1") @DefaultValue("A Default") @Encoded String defaultAndEncoded,
                                          @MatrixParam("dummy2") @DefaultValue("Another Default") String defaultAndNotEncoded,
                                          @FormParam("dummy3") @Encoded String noDefaultButEncoded,
                                          @HeaderParam("dummy4") String noDefaultAndNoEncoded,
                                          String messageBasedParam) {
            }
        }

        AtomicInteger indexer = new AtomicInteger();
        List<ResourceMethodParam> params = Stream.of(Sample.class.getDeclaredMethods()[0].getParameters())
            .map(p -> ResourceMethodParam.fromParameter(indexer.getAndIncrement(), p, BUILT_IN_PARAM_PROVIDERS))
            .collect(Collectors.toList());

        ResourceMethodParam.RequestBasedParam defaultAndEncoded = (ResourceMethodParam.RequestBasedParam) params.get(0);
        assertThat(defaultAndEncoded.defaultValue(), equalTo("A Default"));
        assertThat(defaultAndEncoded.encodedRequested, is(true));
        assertThat(defaultAndEncoded.key, equalTo("dummy1"));

        ResourceMethodParam.RequestBasedParam defaultAndNotEncoded = (ResourceMethodParam.RequestBasedParam) params.get(1);
        assertThat(defaultAndNotEncoded.defaultValue(), equalTo("Another Default"));
        assertThat(defaultAndNotEncoded.encodedRequested, is(false));
        assertThat(defaultAndNotEncoded.key, equalTo("dummy2"));

        ResourceMethodParam.RequestBasedParam noDefaultButEncoded = (ResourceMethodParam.RequestBasedParam) params.get(2);
        assertThat(noDefaultButEncoded.defaultValue(), is(nullValue()));
        assertThat(noDefaultButEncoded.encodedRequested, is(true));
        assertThat(noDefaultButEncoded.key, equalTo("dummy3"));

        ResourceMethodParam.RequestBasedParam noDefaultAndNoEncoded = (ResourceMethodParam.RequestBasedParam) params.get(3);
        assertThat(noDefaultAndNoEncoded.defaultValue(), is(nullValue()));
        assertThat(noDefaultAndNoEncoded.encodedRequested, is(false));
        assertThat(noDefaultAndNoEncoded.key, equalTo("dummy4"));

        assertThat(params.get(4), instanceOf(ResourceMethodParam.MessageBodyParam.class));
    }

    @Test
    public void canGetStuffFromQueryString() throws IOException {

        @Path("samples")
        class Sample {
            @GET
            public String getIt(@QueryParam("one") String one,
                                @QueryParam("two") @DefaultValue("Some default") String two,
                                @QueryParam("three") @Encoded String three
            ) {
                return one + " / " + two + " / " + three;
            }
        }
        server = httpsServer().addHandler(RestHandlerBuilder.create(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?one=some%20thing%2F&three=some%20thing%2F").toString()))) {
            assertThat(resp.body().string(), equalTo("some thing/ / Some default / some%20thing%2F"));
        }


    }

    @Test
    public void canGetStuffFromTheForm() throws IOException {

        @Path("samples")
        class Sample {
            @POST
            public String postIt(@FormParam("someThing") String something, @FormParam("someThing2") @DefaultValue("Ah hah") String something2) {
                return something + " / " + something2;
            }
        }
        server = httpsServer().addHandler(RestHandlerBuilder.create(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString())
        .post(new FormBody.Builder().add("someThing", "Is here").build())
        )) {
            assertThat(resp.body().string(), equalTo("Is here / Ah hah"));
        }

    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }


}