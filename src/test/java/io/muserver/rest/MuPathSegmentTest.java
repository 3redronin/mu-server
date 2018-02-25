package io.muserver.rest;

import io.muserver.Mutils;
import org.junit.Test;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.util.List;

import static io.muserver.rest.ReadOnlyMultivaluedMap.empty;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

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
        assertThat(segment.toString(Mutils::urlEncode),
            equalTo("something;color=red;color=light%20blu;size=large"));
    }


    @Test
    public void resolveCreatesAMutatedOne() {
        MuPathSegment segment = new MuPathSegment("{hello} wor/ld {suffix}", empty());

        List<MuPathSegment> resolved = segment.resolve("hello", "ni/hao", true);

        assertThat(resolved.size(), is(1));
        assertThat(resolved.get(0).toString(),
            equalTo("ni/hao wor/ld {suffix}"));
    }



    @Test
    public void resolveWithEncodeSlashesFalseReturnsASegmentPerBitWithMatrixParamsGoingToFirstSegment() {
        MultivaluedMap<String, String> matrixParams = new MultivaluedHashMap<>();
        matrixParams.add("color", "red");
        MuPathSegment segment = new MuPathSegment("{hello} wor/ld {suffix}", matrixParams);

        List<MuPathSegment> resolved = segment.resolve("hello", "ni/hao", false);

        assertThat(resolved.size(), is(3));
        assertThat(resolved.get(0).toString(), equalTo("ni;color=red"));
        assertThat(resolved.get(0).getMatrixParameters(), equalTo(matrixParams));
        assertThat(resolved.get(1).toString(), equalTo("hao wor"));
        assertThat(resolved.get(1).getMatrixParameters().entrySet(), hasSize(0));
        assertThat(resolved.get(2).toString(), equalTo("ld {suffix}"));
        assertThat(resolved.get(2).getMatrixParameters().entrySet(), hasSize(0));
    }

}