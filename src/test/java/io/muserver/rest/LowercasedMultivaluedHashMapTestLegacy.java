package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.util.Collections;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class LowercasedMultivaluedHashMapTestLegacy {
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
