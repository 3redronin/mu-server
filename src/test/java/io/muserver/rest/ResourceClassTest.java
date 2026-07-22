package io.muserver.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static java.net.URI.create;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
        assertThat(resourceMethod.methodHandle.isBridge(), equalTo(false));
        assertThat(resourceMethod.genericReturnType.getTypeName(), equalTo("java.util.List<java.lang.String>"));
    }

    @Test
    public void genericInterfaceParameterAnnotationsAreInherited() {
        ResourceClass resourceClass = ResourceClass.fromObject(new StringLookupResource(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        ResourceMethod resourceMethod = resourceClass.resourceMethods.get(0);
        assertThat(resourceMethod.methodHandle.isBridge(), equalTo(false));
        assertThat(resourceMethod.methodHandle.getParameterTypes()[0], equalTo(String.class));
        assertThat(resourceMethod.params.get(0).source, equalTo(ResourceMethodParam.ValueSource.PATH_PARAM));
    }

    @Test
    public void genericParentInterfaceParameterAnnotationsAreInherited() {
        ResourceClass resourceClass = ResourceClass.fromObject(new ChildStringLookupResource(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        ResourceMethod resourceMethod = resourceClass.resourceMethods.get(0);
        assertThat(resourceMethod.methodHandle.isBridge(), equalTo(false));
        assertThat(resourceMethod.methodHandle.getParameterTypes()[0], equalTo(String.class));
        assertThat(resourceMethod.params.get(0).source, equalTo(ResourceMethodParam.ValueSource.PATH_PARAM));
    }

    @Test
    public void interfaceAnnotationsAreFoundWhenTheImplementationComesFromASuperclass() {
        ResourceClass resourceClass = ResourceClass.fromObject(new InterfaceResourceWithInheritedImplementation(), ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS, customizer);

        assertThat(resourceClass.resourceMethods, hasSize(1));
        assertThat(resourceClass.resourceMethods.get(0).methodHandle.getDeclaringClass(), equalTo(UnannotatedImplementation.class));
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

}
