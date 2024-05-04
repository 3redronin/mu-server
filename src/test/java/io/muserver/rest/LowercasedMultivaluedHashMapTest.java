package io.muserver.rest;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.Test;

import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class LowercasedMultivaluedHashMapTest {
    private LowercasedMultivaluedHashMap<Object> map = new LowercasedMultivaluedHashMap<>();

    @Test
    public void itIsCaseInsensitive() {
        map.put("Allow", asList("GET", "POST"));
        assertThat(map.get("allow"), contains("GET", "POST"));
    }

    @Test
    public void mapsCanBeAdded() {
        map.put("Blah", Collections.singletonList("hello"));

        MultivaluedMap<String,Object> another = new MultivaluedHashMap<>();
        another.add("blAH", "world");
        map.putAll(another);

        map.addAll("BLah", "is", "Hello");

        assertThat(map.get("blah"), contains("world", "is", "Hello"));
    }

}