package io.muserver.rest;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ObjWithTypeTest {

    @Test
    public void genericTypeAndTypeAreSameIfNoGenerics() {
        ObjWithType hello = ObjWithType.objType("Hello");
        assertThat(hello.entity, equalTo("Hello"));
        assertThat(hello.type, equalTo(String.class));
        assertThat(hello.genericType, equalTo(String.class));
    }

}