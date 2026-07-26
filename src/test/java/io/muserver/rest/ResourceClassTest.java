package io.muserver.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.junit.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.net.URI.create;
import static java.util.Collections.emptyList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class ResourceClassTest {

    private final SchemaObjectCustomizer customizer = new CompositeSchemaObjectCustomizer(emptyList());

    @Test
    public void canCreate() {
        ResourceClass rc = ResourceClass.fromObject(new Fruit(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(rc.matches(create("api/fruit")), equalTo(false));
        assertThat(rc.matches(create("api/fruits")), equalTo(true));
        assertThat(rc.matches(create("api/fruits?yeah=yeah")), equalTo(true));
        assertThat(rc.matches(create("api/fruits/orange")), equalTo(true));
    }

    @Test
    public void pathParamsCanBeInheritedIfThereAreNoJaxAnnotations() {
        ResourceClass rc = ResourceClass.fromObject(new ConcreteWidget(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(rc.matches(create("api/widgets")), equalTo(true));
    }

    @Test
    public void canIdentifyNonSubResourceMethods() {
        @Path("/{s:.*}")
        class Optionsy {
            @GET
            public String optionsGet() { return ""; }
            @OPTIONS
            public String options() { return ""; }
            @Path("more")
            @OPTIONS
            public String more() { return ""; }
        }

        ResourceClass resourceClass = ResourceClass.fromObject(new Optionsy(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        assertThat(resourceClass.resourceMethods, hasSize(3));
        assertThat(resourceClass.nonSubResourceMethods(), hasSize(2));
        assertThat(resourceClass.subResourceMethods(), hasSize(1));

    }

    @Test
    public void genericOverridesDoNotRegisterSyntheticBridgeMethods() {
        ResourceClass resourceClass = ResourceClass.fromObject(new StringListResource(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        ResourceMethod resourceMethod = resourceClass.resourceMethods.get(0);
        assertThat(resourceMethod.methodHandle().isBridge(), equalTo(false));
        assertThat(resourceMethod.genericReturnType().getTypeName(), equalTo("java.util.List<java.lang.String>"));
    }

    @Test
    public void genericInterfaceParameterAnnotationsAreInherited() {
        ResourceClass resourceClass = ResourceClass.fromObject(new StringLookupResource(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        ResourceMethod resourceMethod = resourceClass.resourceMethods.get(0);
        assertThat(resourceMethod.methodHandle().isBridge(), equalTo(false));
        assertThat(resourceMethod.methodHandle().getParameterTypes()[0], equalTo(String.class));
        assertThat(resourceMethod.params.get(0).source(), equalTo(ResourceMethodParam.ValueSource.PATH_PARAM));
    }

    @Test
    public void genericParentInterfaceParameterAnnotationsAreInherited() {
        ResourceClass resourceClass = ResourceClass.fromObject(new ChildStringLookupResource(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        ResourceMethod resourceMethod = resourceClass.resourceMethods.get(0);
        assertThat(resourceMethod.methodHandle().isBridge(), equalTo(false));
        assertThat(resourceMethod.methodHandle().getParameterTypes()[0], equalTo(String.class));
        assertThat(resourceMethod.params.get(0).source(), equalTo(ResourceMethodParam.ValueSource.PATH_PARAM));
    }

    @Test
    public void interfaceAnnotationsAreFoundWhenTheImplementationComesFromASuperclass() {
        ResourceClass resourceClass = ResourceClass.fromObject(new InterfaceResourceWithInheritedImplementation(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        assertThat(resourceClass.resourceMethods.get(0).methodHandle().getDeclaringClass(), equalTo(UnannotatedImplementation.class));
    }

    @Test
    public void dynamicSubResourcesReuseIntrospectionButRemainBoundToTheirInstances() throws Exception {
        ResourceClass root = ResourceClass.fromObject(new DynamicRoot(),
            ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);
        ResourceMethod locator = root.resourceMethods.get(0);

        ResourceClass first = ResourceClass.forSubResourceLocator(locator, DynamicChild.class,
            new DynamicChild("first"), customizer, ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS);
        ResourceClass second = ResourceClass.forSubResourceLocator(locator, DynamicChild.class,
            new DynamicChild("second"), customizer, ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS);

        assertSame(first.introspection, second.introspection);
        assertThat(first.resourceMethods.get(0).invoke(), equalTo("first"));
        assertThat(second.resourceMethods.get(0).invoke(), equalTo("second"));
    }

    @Test
    public void converterSelectionRemainsBoundToEachHandlerConfiguration() {
        ResourceClass first = ResourceClass.fromObject(new ConverterResource(),
            providersWithPrefix("first:"), customizer);
        ResourceClass second = ResourceClass.fromObject(new ConverterResource(),
            providersWithPrefix("second:"), customizer);

        assertSame(first.introspection, second.introspection);
        ResourceMethodParam.RequestBasedParam firstParam =
            (ResourceMethodParam.RequestBasedParam) first.resourceMethods.get(0).params.get(0);
        ResourceMethodParam.RequestBasedParam secondParam =
            (ResourceMethodParam.RequestBasedParam) second.resourceMethods.get(0).params.get(0);
        assertThat(firstParam.defaultValue(), equalTo(new ConvertedValue("first:value")));
        assertThat(secondParam.defaultValue(), equalTo(new ConvertedValue("second:value")));
    }

    @Test
    public void cachedCollectionsAreImmutable() {
        ResourceClassIntrospection model = ResourceClassIntrospection.forClass(ConverterResource.class);
        ResourceClassIntrospection.MethodInfo method = model.methods.get(0);
        ResourceMethodParam.Introspection param = method.params.get(0);

        assertThrows(UnsupportedOperationException.class, () -> model.methods.add(method));
        assertThrows(UnsupportedOperationException.class,
            () -> method.directlyProduces.add(MediaType.APPLICATION_JSON_TYPE));
        assertThrows(UnsupportedOperationException.class, () -> method.params.add(param));
        assertThrows(UnsupportedOperationException.class,
            () -> method.nameBindingAnnotations.add(Deprecated.class));
        assertThrows(UnsupportedOperationException.class,
            () -> method.methodAnnotations.add(null));
        assertThrows(UnsupportedOperationException.class,
            () -> param.annotations.add(null));
        assertThrows(UnsupportedOperationException.class,
            () -> model.visibilityWarnings.add("warning"));
    }

    @Test
    public void concurrentInitialAccessReturnsOneCorrectModel() throws Exception {
        int threadCount = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResourceClassIntrospection>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return ResourceClassIntrospection.forClass(ConcurrentResource.class);
                }));
            }
            start.countDown();
            ResourceClassIntrospection expected = futures.get(0).get();
            for (Future<ResourceClassIntrospection> future : futures) {
                assertSame(expected, future.get());
            }
            assertThat(expected.methods, hasSize(1));
            assertThat(expected.methods.get(0).httpMethod, equalTo(io.muserver.Method.GET));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void warnsAboutNonPublicResourceMethodsAndLocators() {
        List<String> warnings = ResourceClass.nonPublicResourceMethodWarnings(ResourceWithNonPublicMethods.class);

        assertThat(warnings, hasSize(4));
        assertThat(warnings.get(0), containsString("ResourceWithNonPublicMethods.packagePrivateResourceMethod()"));
        assertThat(warnings.get(1), containsString("ResourceWithNonPublicMethods.privateResourceMethod()"));
        assertThat(warnings.get(2), containsString("ResourceWithNonPublicMethods.protectedLocator()"));
        assertThat(warnings.get(3), containsString("ResourceWithNonPublicMethods.protectedSubResourceMethod()"));
        for (String warning : warnings) {
            assertThat(warning, containsString("cannot itself be exposed as a resource method or sub-resource locator because only public methods may be exposed."));
        }
    }

    @Test
    public void cachedGenericTypesAreResolvedAgainstTheConcreteClass() {
        ResourceClassIntrospection classModel =
            ResourceClassIntrospection.forClass(StringListResource.class);
        assertThat(classModel.methods.get(0).genericReturnType.getTypeName(),
            equalTo("java.util.List<java.lang.String>"));

        ResourceClassIntrospection interfaceModel =
            ResourceClassIntrospection.forClass(StringLookupResource.class);
        assertThat(interfaceModel.methods.get(0).params.get(0).source,
            equalTo(ResourceMethodParam.ValueSource.PATH_PARAM));
        assertThat(interfaceModel.methods.get(0).annotationSource.getDeclaringClass(),
            equalTo(GenericLookupResource.class));
    }

    private static List<ParamConverterProvider> providersWithPrefix(String prefix) {
        ParamConverterProvider provider = new ParamConverterProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType,
                                                       Annotation[] annotations) {
                if (rawType != ConvertedValue.class) {
                    return null;
                }
                return (ParamConverter<T>) new ParamConverter<ConvertedValue>() {
                    @Override
                    public ConvertedValue fromString(String value) {
                        return new ConvertedValue(prefix + value);
                    }

                    @Override
                    public String toString(ConvertedValue value) {
                        return value.value;
                    }
                };
            }
        };
        return Arrays.asList(provider, new BuiltInParamConverterProvider());
    }

    @Test
    public void doesNotWarnAboutPublicOrUnrelatedAnnotatedMethods() {
        assertThat(ResourceClass.nonPublicResourceMethodWarnings(ResourceWithoutVisibilityProblems.class), empty());
    }

    @Test
    public void warnsAboutNonPublicAnnotatedMethodsOnSuperclasses() {
        assertThat(ResourceClass.nonPublicResourceMethodWarnings(ResourceWithInheritedVisibilityProblem.class),
            contains(containsString("BaseResourceWithVisibilityProblem.hidden()")));
    }

    @Test
    public void warningDoesNotClaimInheritedAnnotationSourceIsIgnored() {
        ResourceClass resourceClass = ResourceClass.fromObject(new ResourceWithPublicOverride(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        assertThat(ResourceClass.nonPublicResourceMethodWarnings(ResourceWithPublicOverride.class),
            contains(containsString("BaseResourceWithPublicOverride.inherited() cannot itself be exposed")));
    }

    @Test
    public void onlyLogsVisibilityWarningsOncePerResourceClass() {
        assertThat(ResourceClass.shouldLogVisibilityWarnings(ResourceForDeduplicationTest.class), equalTo(true));
        assertThat(ResourceClass.shouldLogVisibilityWarnings(ResourceForDeduplicationTest.class), equalTo(false));
    }

    @Path("/api/fruits")
    private static class Fruit {

        @GET
        public String getAll() {
            return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
        }

        @Path("/:name")
        @GET
        public String get(@PathParam("name") String name) {
            switch (name) {
                case "apple":
                    return "{ \"name\": \"apple\" }";
                case "orange":
                    return "{ \"name\": \"orange\" }";
            }
            return "not found";
        }

        public void notEligible() {
        }

    }

    @Path("/api/widgets")
    private static abstract class BaseWidgetResource {

    }
    private static class ConcreteWidget extends BaseWidgetResource {

    }

    private static abstract class GenericResource<T> {
        @GET
        public abstract T get();
    }

    @Path("/api/strings")
    private static class StringListResource extends GenericResource<List<String>> {
        @Override
        public List<String> get() {
            return Collections.emptyList();
        }
    }

    private interface GenericLookupResource<T> {
        @GET
        @Path("{id}")
        String get(@PathParam("id") T id);
    }

    @Path("/api/lookup")
    private static class StringLookupResource implements GenericLookupResource<String> {
        @Override
        public String get(String id) {
            return id;
        }

        public String get(Integer id) {
            return String.valueOf(id);
        }
    }

    private interface ChildGenericLookupResource<T> extends GenericLookupResource<T> { }

    @Path("/api/child-lookup")
    private static class ChildStringLookupResource implements ChildGenericLookupResource<String> {
        @Override
        public String get(String id) {
            return id;
        }
    }

    private interface AnnotatedResourceMethod {
        @GET
        String get();
    }

    private static class UnannotatedImplementation {
        public String get() {
            return "hello";
        }
    }

    @Path("/api/inherited-implementation")
    private static class InterfaceResourceWithInheritedImplementation extends UnannotatedImplementation implements AnnotatedResourceMethod { }

    @Path("/dynamic")
    private static class DynamicRoot {
        @Path("{id}")
        public DynamicChild child() {
            return new DynamicChild("unused");
        }
    }

    private static class DynamicChild {
        private final String value;

        private DynamicChild(String value) {
            this.value = value;
        }

        @GET
        public String get() {
            return value;
        }
    }

    private static final class ConvertedValue {
        private final String value;

        private ConvertedValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ConvertedValue
                && value.equals(((ConvertedValue) other).value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    @Path("/converter")
    private static class ConverterResource {
        @GET
        public String get(@QueryParam("value") @jakarta.ws.rs.DefaultValue("value") ConvertedValue value) {
            return value.value;
        }
    }

    @Path("/concurrent")
    private static class ConcurrentResource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Path("/api/non-public")
    private static class ResourceWithNonPublicMethods {
        @GET
        String packagePrivateResourceMethod() {
            return "";
        }

        @GET
        private String privateResourceMethod() {
            return "";
        }

        @Path("locator")
        protected Object protectedLocator() {
            return new Object();
        }

        @CustomGET
        @Path("custom")
        protected String protectedSubResourceMethod() {
            return "";
        }
    }

    @Path("/api/public")
    private static class ResourceWithoutVisibilityProblems {
        @GET
        public String publicResourceMethod() {
            return "";
        }

        @Produces("text/plain")
        private String helper() {
            return "";
        }
    }

    private static class BaseResourceWithVisibilityProblem {
        @GET
        protected String hidden() {
            return "";
        }
    }

    @Path("/api/inherited-problem")
    private static class ResourceWithInheritedVisibilityProblem extends BaseResourceWithVisibilityProblem { }

    private static class BaseResourceWithPublicOverride {
        @GET
        protected String inherited() {
            return "";
        }
    }

    @Path("/api/public-override")
    private static class ResourceWithPublicOverride extends BaseResourceWithPublicOverride {
        @Override
        public String inherited() {
            return "";
        }
    }

    private static class ResourceForDeduplicationTest { }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod(HttpMethod.GET)
    private @interface CustomGET { }

}
