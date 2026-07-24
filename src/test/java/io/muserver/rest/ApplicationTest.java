package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class ApplicationTest {

    private MuServer server;

    @After
    public void stop() {
        stopAndCheck(server);
    }

    @Test
    public void singletonResourcesAndProvidersAreRegistered() {
        SampleResource resource = new SampleResource();
        SupportedProvider provider = new SupportedProvider();
        Application application = singletonApplication(resource, provider);

        RestHandlerBuilder builder = RestHandlerBuilder.fromApplication(application);

        assertThat(builder.resources(), contains(resource));
        assertThat(builder.customReaders(), contains(provider));
        assertThat(builder.customWriters(), contains(provider));
        assertThat(builder.customParamConverterProviders(), contains(provider));
        assertThat(builder.exceptionMappers(), hasEntry(SampleException.class, provider));
        assertThat(builder.requestFilters(), hasSize(1));
        assertThat(builder.responseFilters(), hasSize(1));
        assertThat(builder.readerInterceptors(), hasSize(1));
        assertThat(builder.writerInterceptors(), hasSize(1));
    }

    @Test
    public void providerClassesAreCreatedAsApplicationSingletons() {
        Application application = new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(SupportedProvider.class);
            }
        };

        RestHandlerBuilder builder = RestHandlerBuilder.fromApplication(application);

        assertThat(builder.customReaders(), hasSize(1));
        SupportedProvider provider = (SupportedProvider) builder.customReaders().get(0);
        assertThat(builder.customWriters(), contains(provider));
        assertThat(builder.customParamConverterProviders(), contains(provider));
        assertThat(builder.exceptionMappers(), hasEntry(SampleException.class, provider));
    }

    @Test
    public void singletonTakesPrecedenceOverClassRegistration() {
        SupportedProvider singleton = new SupportedProvider();
        Application application = new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(SupportedProvider.class);
            }

            @Override
            public Set<Object> getSingletons() {
                return Set.of(singleton);
            }
        };

        RestHandlerBuilder builder = RestHandlerBuilder.fromApplication(application);

        assertThat(builder.customReaders(), contains(singleton));
    }

    @Test
    public void resourceClassesAreRejectedBecauseMuDoesNotSupportPerRequestResources() {
        Application application = new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(SampleResource.class);
            }
        };

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
            () -> RestHandlerBuilder.fromApplication(application));

        assertThat(error.getMessage(), containsString("per-request"));
        assertThat(error.getMessage(), containsString(SampleResource.class.getName()));
    }

    @Test
    public void applicationPathIsRejectedBecauseAHandlerBuilderCannotMountItself() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
            () -> RestHandlerBuilder.fromApplication(new PathAnnotatedApplication()));

        assertThat(error.getMessage(), containsString("@ApplicationPath"));
        assertThat(error.getMessage(), containsString("ContextHandlerBuilder"));
    }

    @Test
    public void unsupportedComponentsAndPropertiesAreRejected() {
        Application unsupportedComponent = singletonApplication(new Object());
        IllegalArgumentException componentError = assertThrows(IllegalArgumentException.class,
            () -> RestHandlerBuilder.fromApplication(unsupportedComponent));
        assertThat(componentError.getMessage(), containsString(Object.class.getName()));

        Application properties = new Application() {
            @Override
            public Map<String, Object> getProperties() {
                return Map.of("example", true);
            }
        };
        UnsupportedOperationException propertyError = assertThrows(UnsupportedOperationException.class,
            () -> RestHandlerBuilder.fromApplication(properties));
        assertThat(propertyError.getMessage(), containsString("properties"));
    }

    @Test
    public void duplicateSingletonClassesAreRejected() {
        Application application = singletonApplication(new SupportedProvider(), new SupportedProvider());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> RestHandlerBuilder.fromApplication(application));

        assertThat(error.getMessage(), containsString("more than one singleton"));
        assertThat(error.getMessage(), containsString(SupportedProvider.class.getName()));
    }

    @Test
    public void clientOnlyComponentsAreIgnoredAndNullCollectionsAreEmpty() {
        Application application = new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return null;
            }

            @Override
            public Set<Object> getSingletons() {
                return Set.of(new ClientOnlyFilter());
            }

            @Override
            public Map<String, Object> getProperties() {
                return null;
            }
        };

        RestHandlerBuilder builder = RestHandlerBuilder.fromApplication(application);

        assertThat(builder.resources(), is(empty()));
        assertThat(builder.requestFilters(), is(empty()));
    }

    @Test
    public void applicationComponentsParticipateInRequestProcessing() throws IOException {
        Application application = singletonApplication(new SampleResource(), new SupportedProvider());
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.fromApplication(application))
            .start();

        RequestBody body = RequestBody.create(okhttp3.MediaType.get("application/example"), "body");
        try (Response response = call(request(server.uri().resolve("/sample/echo?value=query")).post(body))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(),
                is("query-converted:filtered:body-read-reader-interceptor-response-filter-writer-interceptor"));
        }

        try (Response response = call(request(server.uri().resolve("/sample/fail")))) {
            assertThat(response.code(), is(409));
            assertThat(response.body().string(), is("mapped"));
        }
    }

    @Test
    public void nameBindingOnApplicationMakesMatchingProviderGlobal() throws IOException {
        BoundResponseFilter filter = new BoundResponseFilter();
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.fromApplication(new GloballyBoundApplication(filter)))
            .start();

        try (Response response = call(request(server.uri().resolve("/binding/unbound")))) {
            assertThat(response.code(), is(200));
            assertThat(response.header("X-Application-Binding"), is("applied"));
        }
    }

    @Test
    public void nameBindingIsInheritedFromGenericParentInterface() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.fromApplication(singletonApplication(
                new GenericBindingResource(), new GenericBindingResponseFilter())))
            .start();

        try (Response response = call(request(server.uri().resolve("/generic-binding/value?value=hello")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), is("hello"));
            assertThat(response.header("X-Generic-Binding"), is("applied"));
        }
    }

    @Test
    public void applicationComponentPriorityIsRetainedWhenBuilderIsCustomized() throws IOException {
        List<String> calls = new ArrayList<>();
        server = ServerUtils.httpsServerForTest()
            .addHandler(RestHandlerBuilder.fromApplication(new PrioritizedApplication(calls))
                .addRequestFilter(new LaterRequestFilter(calls)))
            .start();

        try (Response response = call(request(server.uri().resolve("/binding/unbound")))) {
            assertThat(response.code(), is(200));
            assertThat(calls, contains("application", "builder"));
        }
    }

    private static Application singletonApplication(Object... components) {
        return new Application() {
            @Override
            public Set<Object> getSingletons() {
                Set<Object> result = new LinkedHashSet<>();
                for (Object component : components) {
                    result.add(component);
                }
                return result;
            }
        };
    }

    @ApplicationPath("api")
    private static class PathAnnotatedApplication extends Application {
    }

    @NameBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    private @interface ApplicationBinding {
    }

    @ApplicationBinding
    private static class GloballyBoundApplication extends Application {
        private final BoundResponseFilter filter;

        private GloballyBoundApplication(BoundResponseFilter filter) {
            this.filter = filter;
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new BindingResource(), filter);
        }
    }

    @Path("binding")
    public static class BindingResource {
        @GET
        @Path("unbound")
        public String unbound() {
            return "response";
        }
    }

    @ApplicationBinding
    private static class BoundResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Application-Binding", "applied");
        }
    }

    @NameBinding
    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    private @interface GenericBinding {
    }

    private interface GenericBindingParent<T> {
        @GET
        @Path("value")
        @GenericBinding
        String value(@QueryParam("value") T value);
    }

    private interface GenericBindingChild<T> extends GenericBindingParent<T> {
    }

    @Path("generic-binding")
    public static class GenericBindingResource implements GenericBindingChild<String> {
        @Override
        public String value(String value) {
            return value;
        }
    }

    @GenericBinding
    private static class GenericBindingResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Generic-Binding", "applied");
        }
    }

    @ApplicationBinding
    private static class PrioritizedApplication extends Application {
        private final List<String> calls;

        private PrioritizedApplication(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new BindingResource(), new EarlyApplicationRequestFilter(calls));
        }
    }

    @Priority(100)
    @ApplicationBinding
    private static class EarlyApplicationRequestFilter implements ContainerRequestFilter {
        private final List<String> calls;

        private EarlyApplicationRequestFilter(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            calls.add("application");
        }
    }

    @Priority(200)
    private static class LaterRequestFilter implements ContainerRequestFilter {
        private final List<String> calls;

        private LaterRequestFilter(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            calls.add("builder");
        }
    }

    @Path("sample")
    public static class SampleResource {
        @POST
        @Path("echo")
        @Consumes("application/example")
        @Produces("application/example")
        public Payload echo(@QueryParam("value") Converted converted, @HeaderParam("X-Application") String filtered,
                            Payload payload) {
            return new Payload(converted.value + ":" + filtered + ":" + payload.value);
        }

        @GET
        @Path("fail")
        public String fail() {
            throw new SampleException();
        }
    }

    public static class SampleException extends RuntimeException {
    }

    private static class Converted {
        private final String value;

        private Converted(String value) {
            this.value = value;
        }
    }

    private static class Payload {
        private final String value;

        private Payload(String value) {
            this.value = value;
        }
    }

    public static class SupportedProvider implements MessageBodyReader<Payload>, MessageBodyWriter<Payload>,
        ParamConverterProvider, ExceptionMapper<SampleException>, ContainerRequestFilter, ContainerResponseFilter,
        ReaderInterceptor, WriterInterceptor {

        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Payload.class;
        }

        @Override
        public Payload readFrom(Class<Payload> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                                MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            return new Payload(new String(entityStream.readAllBytes(), StandardCharsets.UTF_8) + "-read");
        }

        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Payload.class;
        }

        @Override
        public long getSize(Payload value, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType) {
            return value.value.length();
        }

        @Override
        public void writeTo(Payload value, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                            OutputStream entityStream) throws IOException {
            entityStream.write(value.value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType != Converted.class) {
                return null;
            }
            return (ParamConverter<T>) new ParamConverter<Converted>() {
                @Override
                public Converted fromString(String value) {
                    return new Converted(value + "-converted");
                }

                @Override
                public String toString(Converted value) {
                    return value.value;
                }
            };
        }

        @Override
        public jakarta.ws.rs.core.Response toResponse(SampleException exception) {
            return jakarta.ws.rs.core.Response.status(409).entity("mapped").build();
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Application", "filtered");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            if (responseContext.getEntity() instanceof Payload) {
                Payload payload = (Payload) responseContext.getEntity();
                responseContext.setEntity(new Payload(payload.value + "-response-filter"));
            }
        }

        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            Payload payload = (Payload) context.proceed();
            return new Payload(payload.value + "-reader-interceptor");
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            if (context.getEntity() instanceof Payload) {
                Payload payload = (Payload) context.getEntity();
                context.setEntity(new Payload(payload.value + "-writer-interceptor"));
            }
            context.proceed();
        }
    }

    @ConstrainedTo(RuntimeType.CLIENT)
    private static class ClientOnlyFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
        }
    }
}
