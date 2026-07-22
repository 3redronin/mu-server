package io.muserver.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.junit.Test;

import java.lang.reflect.Method;

import static io.muserver.rest.JaxMethodLocator.getMethodThatHasJaxRSAnnotations;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class JaxMethodLocatorTest {

    @SuppressWarnings("unused")
    private @interface NonJaxAnnotation {}

    @Path("hi")
    private interface InterfaceWithAnnotation {
        @Path("qu")
        void go();
    }

    private interface ParameterAnnotatedBase {
        @GET
        void get(@PathParam("id") String id);
    }

    private static class ParameterAnnotatedImplementation implements ParameterAnnotatedBase {
        @Override
        public void get(@QueryParam("id") String id) { }
    }

    private interface InterfaceInheritedThroughSuperclass {
        @Path("superclass-interface")
        void go();
    }

    private interface DirectInterface {
        @Path("direct-interface")
        void go();
    }

    private static class InterfaceBackedSuperclass implements InterfaceInheritedThroughSuperclass {
        @Override
        public void go() { }
    }

    private static class SubclassWithDirectInterface extends InterfaceBackedSuperclass implements DirectInterface { }

    @Test
    public void returnsTheGivenMethodIfNoAnnotations() {
        class SuperClass{
            void go() {}
        }
        class ImplClass extends SuperClass{
            void go() {}
        }
        class AnotherImplClass extends SuperClass{
            @Consumes
            void go() {}
        }
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(ImplClass.class)), equalTo(goMethod(ImplClass.class)));
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(AnotherImplClass.class)), equalTo(goMethod(AnotherImplClass.class)));
    }

    @Test
    public void returnsTheSuperMethodIfItHasAnnotation() {
        class SuperClass{
            @Path("/goeth")
            void go() {}
        }
        class ImplClass extends SuperClass{
            void go() {}
        }
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(ImplClass.class)), equalTo(goMethod(SuperClass.class)));
    }

    @Test
    public void canGetAnnotationsFromInterfaces() {
        class ImplClass implements InterfaceWithAnnotation {
            public void go() {}
        }
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(ImplClass.class)), equalTo(goMethod(InterfaceWithAnnotation.class)));
    }
    @Test
    public void classesArePreferredOverInterfaces() {
        class ImplClass implements InterfaceWithAnnotation {
            @Path("ha")
            public void go() {}
        }
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(ImplClass.class)), equalTo(goMethod(ImplClass.class)));
    }

    @Test
    public void baseClassesArePreferredOverInterfaces() {
        class BaseClass {
            @Path("had")
            public void go() {}
        }
        class BaseClassImpl extends BaseClass implements InterfaceWithAnnotation {
            public void go() {}
        }
        assertThat(getMethodThatHasJaxRSAnnotations(goMethod(BaseClassImpl.class)), equalTo(goMethod(BaseClass.class)));
    }

    @Test
    public void parameterAnnotationsOnImplementationPreventInheritance() throws NoSuchMethodException {
        Method implementation = ParameterAnnotatedImplementation.class.getDeclaredMethod("get", String.class);
        assertThat(getMethodThatHasJaxRSAnnotations(implementation), equalTo(implementation));
    }

    @Test
    public void superclassHierarchyIsPreferredOverADirectInterface() throws NoSuchMethodException {
        Method inheritedMethod = SubclassWithDirectInterface.class.getMethod("go");
        Method annotationSource = InterfaceInheritedThroughSuperclass.class.getDeclaredMethod("go");

        assertThat(getMethodThatHasJaxRSAnnotations(inheritedMethod, SubclassWithDirectInterface.class),
            equalTo(annotationSource));
    }

    private static Method goMethod(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethod("go");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }


}
