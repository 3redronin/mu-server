package io.muserver.rest;

import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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

    @Test
    public void declaredGenericTypeIsUsedForDirectEntities() throws NoSuchMethodException {
        Type declaredType = Sample.class.getDeclaredMethod("direct").getGenericReturnType();

        ObjWithType result = ObjWithType.objType(new ArrayList<String>(), declaredType);

        assertThat(result.type, equalTo(ArrayList.class));
        assertThat(result.genericType, equalTo(declaredType));
    }

    @Test
    public void responseEntityMetadataDoesNotComeFromTheResourceMethod() throws NoSuchMethodException {
        Type declaredType = Sample.class.getDeclaredMethod("response").getGenericReturnType();

        ObjWithType result = ObjWithType.objType(new JaxRSResponse.Builder().entity(new ArrayList<String>()).build(), declaredType);

        assertThat(result.type, equalTo(ArrayList.class));
        assertThat(result.genericType, equalTo(ArrayList.class));
    }

    @Test
    public void genericEntityMetadataTakesPrecedenceOverTheResourceMethod() throws NoSuchMethodException {
        Type declaredType = Sample.class.getDeclaredMethod("direct").getGenericReturnType();
        GenericEntity<List<Integer>> entity = new GenericEntity<List<Integer>>(new ArrayList<>()) { };

        ObjWithType result = ObjWithType.objType(entity, declaredType);

        assertThat(result.type, equalTo(ArrayList.class));
        assertThat(result.genericType, equalTo(entity.getType()));
    }

    private static class Sample {
        List<String> direct() {
            return null;
        }

        Response response() {
            return null;
        }
    }

}
