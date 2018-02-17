package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;

import java.util.HashMap;
import java.util.Map;

import static io.muserver.rest.ReadOnlyMultivaluedMap.empty;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
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
        MuPathSegment segment = new MuPathSegment("something", params);
        assertThat(segment.pathParameters().isEmpty(), is(true));
        assertThat(segment.toString(),
            equalTo("something;color=red;color=light%20blu;size=large"));
    }

    @Test
    public void pathBitsCanContainTemplateParams() {
        MuPathSegment segment = new MuPathSegment("{hello} world {suffix}", empty());
        assertThat(segment.pathParameters(), equalTo(asList("hello", "suffix")));
        Map<String,Object> values = new HashMap<>();
        values.put("hello", "ni/hao");
        values.put("suffix", "party%20people");
        assertThat(segment.render(values, true, true), equalTo("ni%2Fhao%20world%20party%2520people"));
        assertThat(segment.render(values, true, false), equalTo("ni/hao%20world%20party%2520people"));
        assertThat(segment.render(values, false, true), equalTo("ni%2Fhao%20world%20party%20people"));
        assertThat(segment.render(values, false, false), equalTo("ni/hao%20world%20party%20people"));
    }

}