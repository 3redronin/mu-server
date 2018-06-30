package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.Consumes;
import javax.ws.rs.Path;
import java.lang.reflect.Method;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static io.muserver.rest.JaxMethodLocator.getMethodThatHasJaxRSAnnotations;

public class JaxMethodLocatorTest {

    @SuppressWarnings("unused")
    private @interface NonJaxAnnotation {}

    @Path("hi")
    private interface InterfaceWithAnnotation {
        @Path("qu")
        void go();
    }

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

    private static Method goMethod(Class<?> clazz) {
        try {
            return clazz.getDeclaredMethod("go");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

}