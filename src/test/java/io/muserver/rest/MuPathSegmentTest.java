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
        MuPathSegment segment = new MuPathSegment("{hello} wor/ld {suffix}", empty());
        assertThat(segment.pathParameters(), equalTo(asList("hello", "suffix")));
        Map<String,Object> values = new HashMap<>();
        values.put("hello", "ni/hao");
        values.put("suffix", "party%20people");
        assertThat(segment.render(values, true, true, true), equalTo("ni%2Fhao%20wor%2Fld%20party%2520people"));
        assertThat(segment.render(values, true, true, false), equalTo("ni/hao%20wor/ld%20party%2520people"));
        assertThat(segment.render(values, true, false, true), equalTo("ni%2Fhao%20wor%2Fld%20party%20people"));
        assertThat(segment.render(values, true, false, false), equalTo("ni/hao%20wor/ld%20party%20people"));

        String unencoded = segment.render(values, false, false, false);
        assertThat(unencoded, equalTo("ni/hao wor/ld party%20people"));
        // The 2nd and 3rd booleans are ignored when encodePath is false
        assertThat(segment.render(values, false, true, false), equalTo(unencoded));
        assertThat(segment.render(values, false, false, true), equalTo(unencoded));
        assertThat(segment.render(values, false, false, false), equalTo(unencoded));
    }

}