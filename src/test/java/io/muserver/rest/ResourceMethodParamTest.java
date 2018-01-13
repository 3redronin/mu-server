package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.ext.ParamConverterProvider;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ResourceMethodParamTest {


    public static final List<ParamConverterProvider> BUILT_IN_PARAM_PROVIDERS = Collections.singletonList(new BuiltInParamConverterProvider());

    @Test
    public void canFindStuffOut() {

        @SuppressWarnings("unused")
        class Sample {
            public void defaultAndEncoded(@QueryParam("dummy1") @DefaultValue("A Default") @Encoded String defaultAndEncoded,
                                          @MatrixParam("dummy2") @DefaultValue("Another Default") String defaultAndNotEncoded,
                                          @FormParam("dummy3") @Encoded String noDefaultButEncoded,
                                          @HeaderParam("dummy4") String noDefaultAndNoEncoded,
                                          String messageBasedParam) { }
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

}