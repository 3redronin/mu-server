package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.muserver.rest.ReadOnlyMultivaluedMap.empty;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
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
        Map<String, Object> values = new HashMap<>();
        values.put("hello", "ni/hao");
        values.put("suffix", "party%20people");
        assertThat(segment.render(values, true, true, true), equalTo("ni%2Fhao%20wor%2Fld%20party%2520people"));
        assertThat(segment.render(values, true, true, false), equalTo("ni/hao%20wor/ld%20party%2520people"));
        assertThat(segment.render(values, true, false, true), equalTo("ni%2Fhao%20wor%2Fld%20party%20people"));
        assertThat(segment.render(values, true, false, false), equalTo("ni/hao%20wor/ld%20party%20people"));

        assertThat(segment.render(values, false, false, false),
            equalTo("ni/hao wor/ld party%20people"));
        assertThat(segment.render(values, false, true, false), equalTo("ni/hao wor/ld party%2520people"));
        assertThat(segment.render(values, false, false, true), equalTo("ni/hao wor/ld party%20people"));
        assertThat(segment.render(values, false, false, false), equalTo("ni/hao wor/ld party%20people"));
    }

    @Test
    public void resolveCreatesAMutatedOne() {
        MuPathSegment segment = new MuPathSegment("{hello} wor/ld {suffix}", empty());

        Map<String, Object> values = new HashMap<>();
        values.put("hello", "ni/hao");

        List<MuPathSegment> resolved = segment.resolve(values, true);

        assertThat(resolved.size(), is(1));
        assertThat(resolved.get(0).render(emptyMap(), false, true, true),
            equalTo("ni/hao wor/ld {suffix}"));
    }



    @Test
    public void resolveWithEncodeSlashesFalseReturnsASegmentPerBit() {
        MuPathSegment segment = new MuPathSegment("{hello} wor/ld {suffix}", empty());

        Map<String, Object> values = new HashMap<>();
        values.put("hello", "ni/hao");

        List<MuPathSegment> resolved = segment.resolve(values, false);

        assertThat(resolved.size(), is(3));
        assertThat(resolved.get(0).render(emptyMap(), false, true, true),
            equalTo("ni"));
        assertThat(resolved.get(1).render(emptyMap(), false, true, true),
            equalTo("hao wor"));
        assertThat(resolved.get(2).render(emptyMap(), false, true, true),
            equalTo("ld {suffix}"));
    }

}