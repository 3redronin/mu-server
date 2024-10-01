package io.muserver.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static io.muserver.rest.JaxClassLocator.getClassWithJaxRSAnnotations;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class JaxClassLocatorTest {

    private @interface NonJaxAnnotation {}

    @Consumes
    private interface InterfaceWithAnnotation { }
    private interface ChildOfInterfaceWithAnnotation extends InterfaceWithAnnotation { }

    @Test
    public void classLocatorReturnsNullIfNoJaxRSAnnotations() {
        assertThat(getClassWithJaxRSAnnotations(JaxClassLocatorTest.class), is(nullValue()));
    }

    @Test
    public void usesMostSpecificClassWithAnnotations() {
        @Path("/")
        class BaseClass {
        }
        @Path("/blah")
        @NonJaxAnnotation
        class ImplClass extends BaseClass {
        }
        @NonJaxAnnotation
        class ImplClassImpl extends ImplClass {}

        assertThat(getClassWithJaxRSAnnotations(ImplClass.class), equalTo(ImplClass.class));
        assertThat(getClassWithJaxRSAnnotations(ImplClassImpl.class), equalTo(ImplClass.class));
    }

    @Test
    public void usesTheInterfaceIfAvailable() {
        class ClassWithoutAnnotation implements InterfaceWithAnnotation {}
        assertThat(getClassWithJaxRSAnnotations(ClassWithoutAnnotation.class), equalTo(InterfaceWithAnnotation.class));
    }

    @Test
    public void annotationsCannotComeFromInterfaceInheritence() {
        class ClassWithoutAnnotation implements ChildOfInterfaceWithAnnotation {}
        assertThat(getClassWithJaxRSAnnotations(ClassWithoutAnnotation.class), is(nullValue()));
    }
    @Test
    public void superClassesCanHaveAnnotationsComeFromAnInterface() {
        class SuperClass implements InterfaceWithAnnotation{}
        class ClassWithoutAnnotation extends SuperClass {}
        assertThat(getClassWithJaxRSAnnotations(ClassWithoutAnnotation.class), equalTo(InterfaceWithAnnotation.class));
    }
    @Test
    public void superClassesArePreferredOverInterfaces() {
        @Produces
        class SuperClassWithAnnotation {}
        class ClassWithoutAnnotation extends SuperClassWithAnnotation implements InterfaceWithAnnotation {}
        assertThat(getClassWithJaxRSAnnotations(ClassWithoutAnnotation.class), equalTo(SuperClassWithAnnotation.class));
    }

}