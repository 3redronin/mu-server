package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.GenericEntity;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;

public class ObjWithTypeTest {

    @Test
    public void genericTypeAndTypeAreSameIfNoGenerics() {
        ObjWithType hello = ObjWithType.objType("Hello");
        assertThat(hello.entity, equalTo("Hello"));
        assertThat(hello.type, equalTo(String.class));
        assertThat(hello.genericType, equalTo(String.class));
    }
    @Test
    public void theTypeCanBeFoundIfItIsAGeneric() {
        class Dog {}

        List<Dog> dogList = new ArrayList<>();
        dogList.add(new Dog());

        ObjWithType hello = ObjWithType.objType(new GenericEntity<List<Dog>>(dogList) {});

        assertThat(hello.entity, sameInstance(dogList));
        assertThat(hello.type, equalTo(ArrayList.class));
        assertThat(hello.genericType, instanceOf(ParameterizedType.class));
        ParameterizedType pt = (ParameterizedType) hello.genericType;
        assertThat(pt.getActualTypeArguments()[0], equalTo(Dog.class));
        assertThat(pt.getRawType(), equalTo(List.class));
    }

}