package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class MuPathSegmentTest {

    @Test
    public void toStringReturnsPathString() {
        assertThat(new MuPathSegment("something", new MultivaluedHashMap<>()).toString(),
            equalTo("something"));
    }

    @Test
    public void toStringReturnsPathStringWithMatrixParams() {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("color", "red");
        params.add("size", "large");
        params.add("color", "light blu");
        assertThat(new MuPathSegment("something", params).toString(),
            equalTo("something;color=red;color=light%20blu;size=large"));
    }

}