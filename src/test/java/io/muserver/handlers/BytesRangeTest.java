package io.muserver.handlers;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class BytesRangeTest {

    @Test
    public void nonBytesRangeNotSupported() {
        assertThat(BytesRange.parse(1000L, "percent=0-50"), is(empty()));
    }

    @Test
    public void nullOrBlankReturnsEmptyList() {
        assertThat(BytesRange.parse(1000L, null), is(empty()));
        assertThat(BytesRange.parse(1000L, ""), is(empty()));
        assertThat(BytesRange.parse(1000L, "   "), is(empty()));
    }

    @Test
    public void upperBytesRangeCappedAtLengthMinusOne() {
        List<BytesRange> rangeList = BytesRange.parse(1000L, "bytes=0-1000");
        assertThat(rangeList, hasSize(1));
        BytesRange range = rangeList.get(0);
        assertThat(range.from, is(0L));
        assertThat(range.to, is(999L));
    }

    @Test
    public void multipleRangesSupported() {
        List<BytesRange> rangeList = BytesRange.parse(1000L, "bytes=-10, 100-199,800-");
        assertThat(rangeList, hasSize(3));

        BytesRange range0 = rangeList.get(0);
        assertThat(range0.from, is(990L));
        assertThat(range0.to, is(999L));
        assertThat(range0.length(), is(10L));
        assertThat(range0.toString(), is("bytes 990-999/1000"));

        BytesRange range1 = rangeList.get(1);
        assertThat(range1.from, is(100L));
        assertThat(range1.to, is(199L));
        assertThat(range1.length(), is(100L));
        assertThat(range1.toString(), is("bytes 100-199/1000"));

        BytesRange range2 = rangeList.get(2);
        assertThat(range2.from, is(800L));
        assertThat(range2.to, is(999L));
        assertThat(range2.length(), is(200L));
        assertThat(range2.toString(), is("bytes 800-999/1000"));
    }

    @Test
    public void finalByteSupported() {
        BytesRange r = BytesRange.parse(1000L, "bytes=999-999").get(0);
        assertThat(r.from, is(999L));
        assertThat(r.to, is(999L));
        assertThat(r.length(), is(1L));
        assertThat(r.toString(), is("bytes 999-999/1000"));
    }

    @Test
    public void invalidRangesThrow() {
        String[] bads = { "bytes=10", "bytes=1-10,umm", "bytes=bytes" };
        for (String bad : bads) {
            try {
                BytesRange.parse(1000L, bad);
                fail(bad + " should have thrown an exception");
            } catch (Exception e) {
                assertThat("Wrong exception type for " + bad, e, instanceOf(IllegalArgumentException.class));
            }

        }
    }

}