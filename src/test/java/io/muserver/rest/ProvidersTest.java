package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class ProvidersTest {

    private MuServer server;

    @After
    public void stop() {
        stopAndCheck(server);
    }

    @Test
    public void entityProviderLookupsReturnMatchesOrNull() {
        PayloadReader reader = new PayloadReader(null);
        PayloadWriter writer = new PayloadWriter(null);
        JaxRSProviders providers = initializedProviders(
            Collections.singletonList(reader), Collections.singletonList(writer),
            Collections.emptyMap(), Collections.emptyList());

        assertThat(providers.getMessageBodyReader(Payload.class, Payload.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE),
            sameInstance(reader));
        assertThat(providers.getMessageBodyWriter(Payload.class, Payload.class, new Annotation[0], MediaType.TEXT_PLAIN_TYPE),
            sameInstance(writer));
        assertThat(providers.getMessageBodyReader(Payload.class, Payload.class, new Annotation[0], MediaType.APPLICATION_JSON_TYPE),
            nullValue());
        assertThat(providers.getMessageBodyWriter(Payload.class, Payload.class, new Annotation[0], MediaType.APPLICATION_JSON_TYPE),
            nullValue());
    }

    @Test
    public void exceptionMapperLookupUsesTheNearestRegisteredSuperclass() {
        ExceptionMapper<Exception> broad = exception -> jakarta.ws.rs.core.Response.serverError().build();
        ExceptionMapper<IllegalArgumentException> exact = exception -> jakarta.ws.rs.core.Response.status(400).build();
        Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> mappers = new HashMap<>();
        mappers.put(Exception.class, broad);
        mappers.put(IllegalArgumentException.class, exact);
        JaxRSProviders providers = initializedProviders(emptyList(), emptyList(), mappers, emptyList());

        assertThat(providers.getExceptionMapper(IllegalArgumentException.class), sameInstance(exact));
        assertThat(providers.getExceptionMapper(NumberFormatException.class), sameInstance(exact));
        assertThat(providers.getExceptionMapper(IOException.class), sameInstance(broad));
        assertThat(providers.getExceptionMapper(Error.class), nullValue());
    }

    @Test
    public void contextResolversAreFilteredAndComposedInMediaTypeOrder() {
        List<String> calls = new ArrayList<>();
        ContextResolver<Prefix> wildcard = new WildcardPrefixResolver(calls);
        ContextResolver<Prefix> json = new JsonPrefixResolver(calls);
        List<JaxRSProviders.ContextResolverRegistration<?>> registrations = new ArrayList<>();
        registrations.add(new JaxRSProviders.ContextResolverRegistration<>(Prefix.class, wildcard));
        registrations.add(new JaxRSProviders.ContextResolverRegistration<>(Prefix.class, json));
        JaxRSProviders providers = initializedProviders(emptyList(), emptyList(), Collections.emptyMap(), registrations);

        ContextResolver<Prefix> resolver = providers.getContextResolver(Prefix.class, MediaType.APPLICATION_JSON_TYPE);
        assertThat(resolver.getContext(Payload.class).value, equalTo("wildcard"));
        assertThat(calls, contains("json", "wildcard"));

        calls.clear();
        assertThat(resolver.getContext(String.class).value, equalTo("json"));
        assertThat(calls, contains("json"));
        assertThat(providers.getContextResolver(Prefix.class, MediaType.TEXT_XML_TYPE), sameInstance(wildcard));
    }

    @Test
    public void contextResolverLookupReturnsNullWhenTypeOrMediaTypeDoesNotMatch() {
        ContextResolver<Prefix> json = new JsonPrefixResolver(new ArrayList<>());
        List<JaxRSProviders.ContextResolverRegistration<?>> registrations = Collections.singletonList(
            new JaxRSProviders.ContextResolverRegistration<>(Prefix.class, json));
        JaxRSProviders providers = initializedProviders(emptyList(), emptyList(), Collections.emptyMap(), registrations);

        assertThat(providers.getContextResolver(Prefix.class, MediaType.TEXT_PLAIN_TYPE), nullValue());
        assertThat(providers.getContextResolver(CharSequence.class, MediaType.APPLICATION_JSON_TYPE), nullValue());
        assertThat(providers.getContextResolver(Object.class, MediaType.APPLICATION_JSON_TYPE), nullValue());
    }

    @Test
    public void factoriesReceiveTheSameProvidersThatResourcesReceive() throws Exception {
        AtomicReference<Providers> writerProviders = new AtomicReference<>();
        AtomicReference<Providers> readerProviders = new AtomicReference<>();

        @Path("providers")
        class Resource {
            @POST
            @Consumes(MediaType.TEXT_PLAIN)
            @Produces(MediaType.TEXT_PLAIN)
            public Payload echo(Payload payload) {
                return payload;
            }

            @GET
            @Path("same")
            @Produces(MediaType.TEXT_PLAIN)
            public String same(@Context Providers providers) {
                return Boolean.toString(providers == readerProviders.get() && providers == writerProviders.get());
            }
        }

        server = ServerUtils.httpsServerForTest().addHandler(
            restHandler(new Resource())
                .addContextResolver(Prefix.class, new PlainTextPrefixResolver())
                .addCustomReader(providers -> {
                    readerProviders.set(providers);
                    return new PayloadReader(providers);
                })
                .addCustomWriter(providers -> {
                    writerProviders.set(providers);
                    return new PayloadWriter(providers);
                })
                .build()).start();

        try (Response response = call(request()
            .url(server.uri().resolve("/providers").toString())
            .post(RequestBody.create("hello", okhttp3.MediaType.parse("text/plain"))))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), equalTo("context:context:hello"));
        }
        try (Response response = call(request(server.uri().resolve("/providers/same")))) {
            assertThat(response.body().string(), equalTo("true"));
        }
    }

    @Test
    public void providersCannotBeQueriedFromInsideAFactory() {
        AtomicReference<IllegalStateException> error = new AtomicReference<>();
        RestHandlerBuilder builder = new RestHandlerBuilder().addCustomWriter(providers -> {
            error.set(assertThrows(IllegalStateException.class, () -> providers.getExceptionMapper(Exception.class)));
            return new PayloadWriter(providers);
        });

        builder.build();

        assertThat(error.get().getMessage(), equalTo(
            "Providers cannot be queried until RestHandlerBuilder.build() has completed"));
    }

    @Test
    public void factoriesAreInvokedOncePerBuildWithAnIsolatedRegistry() {
        AtomicInteger calls = new AtomicInteger();
        List<Providers> providerInstances = new ArrayList<>();
        RestHandlerBuilder builder = new RestHandlerBuilder().addCustomWriter(providers -> {
            calls.incrementAndGet();
            providerInstances.add(providers);
            return new PayloadWriter(providers);
        });

        builder.build();
        builder.build();

        assertThat(calls.get(), is(2));
        assertThat(providerInstances.get(0), not(sameInstance(providerInstances.get(1))));
    }

    @Test
    public void nullFactoryResultsFailTheBuild() {
        RestHandlerBuilder builder = new RestHandlerBuilder()
            .addCustomWriter(providers -> (MessageBodyWriter<Payload>) null);

        IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

        assertThat(error.getMessage(), equalTo("A custom message body writer factory returned null"));
    }

    private static JaxRSProviders initializedProviders(
        List<MessageBodyReader> readers,
        List<MessageBodyWriter> writers,
        Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers,
        List<JaxRSProviders.ContextResolverRegistration<?>> contextResolvers) {
        JaxRSProviders providers = new JaxRSProviders();
        List<JaxRSProviders.ExceptionMapperRegistration<?>> mapperRegistrations = new ArrayList<>();
        exceptionMappers.forEach((type, mapper) -> addExceptionMapper(mapperRegistrations, type, mapper));
        providers.initialize(new EntityProviders(readers, writers), mapperRegistrations, contextResolvers);
        return providers;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addExceptionMapper(List<JaxRSProviders.ExceptionMapperRegistration<?>> registrations,
                                           Class<? extends Throwable> type,
                                           ExceptionMapper<? extends Throwable> mapper) {
        registrations.add(new JaxRSProviders.ExceptionMapperRegistration(type, mapper, false));
    }

    private static class Payload {
        private final String value;

        private Payload(String value) {
            this.value = value;
        }
    }

    private static class Prefix {
        private final String value;

        private Prefix(String value) {
            this.value = value;
        }
    }

    @Consumes(MediaType.TEXT_PLAIN)
    private static class PayloadReader implements MessageBodyReader<Payload> {
        private final Providers providers;

        private PayloadReader(Providers providers) {
            this.providers = providers;
        }

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.equals(Payload.class);
        }

        @Override
        public Payload readFrom(Class<Payload> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            String value = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
            if (providers == null) {
                return new Payload(value);
            }
            ContextResolver<Prefix> resolver = providers.getContextResolver(Prefix.class, mediaType);
            return new Payload(resolver.getContext(type).value + value);
        }
    }

    @Produces(MediaType.TEXT_PLAIN)
    private static class PayloadWriter implements MessageBodyWriter<Payload> {
        private final Providers providers;

        private PayloadWriter(Providers providers) {
            this.providers = providers;
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type.equals(Payload.class);
        }

        @Override
        public void writeTo(Payload payload, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            String prefix = "";
            if (providers != null) {
                ContextResolver<Prefix> resolver = providers.getContextResolver(Prefix.class, mediaType);
                prefix = resolver.getContext(type).value;
            }
            entityStream.write((prefix + payload.value).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Produces(MediaType.WILDCARD)
    private static class WildcardPrefixResolver implements ContextResolver<Prefix> {
        private final List<String> calls;

        private WildcardPrefixResolver(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Prefix getContext(Class<?> type) {
            calls.add("wildcard");
            return new Prefix("wildcard");
        }
    }

    @Produces(MediaType.APPLICATION_JSON)
    private static class JsonPrefixResolver implements ContextResolver<Prefix> {
        private final List<String> calls;

        private JsonPrefixResolver(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Prefix getContext(Class<?> type) {
            calls.add("json");
            return type.equals(Payload.class) ? null : new Prefix("json");
        }
    }

    @Produces(MediaType.TEXT_PLAIN)
    private static class PlainTextPrefixResolver implements ContextResolver<Prefix> {
        @Override
        public Prefix getContext(Class<?> type) {
            return type.equals(Payload.class) ? new Prefix("context:") : null;
        }
    }
}
