package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.muserver.ParameterizedHeaderWithValue.fromString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;

public class ParameterizedHeaderWithValueTest {

    @Test
    public void valueOnlyIsSupported() {
        List<ParameterizedHeaderWithValue> list = fromString("text/html");
        assertThat(list, hasSize(1));
        ParameterizedHeaderWithValue v = list.get(0);
        assertThat(v.value(), is("text/html"));
        assertThat(v.parameter("encoding"), is(nullValue()));
        assertThat(v.parameter("encoding", "UTF-8"), is("UTF-8"));
        assertThat(v.parameters().entrySet(), hasSize(0));
    }

    @Test
    public void multipleWithParamsCanBeGotten() {
        List<ParameterizedHeaderWithValue> list = fromString("text/html, application/xhtml+xml, application/xml;q=0.9, image/webp, */*;q=0.8");
        assertThat(list, hasSize(5));
        assertThat(list.get(0).value(), is("text/html"));
        assertThat(list.get(1).value(), is("application/xhtml+xml"));
        assertThat(list.get(2).value(), is("application/xml"));
        assertThat(list.get(3).value(), is("image/webp"));
        assertThat(list.get(4).value(), is("*/*"));

        ParameterizedHeaderWithValue xml = list.get(2);
        assertThat(xml.parameters().entrySet(), hasSize(1));
        assertThat(xml.parameter("q"), is("0.9"));
        assertThat(xml.parameter("q", "1.0"), is("0.9"));
    }

    @Test
    public void emptyNullAndBlankStringReturnsEmptyList() {
        assertThat(fromString(null), hasSize(0));
        assertThat(fromString(""), hasSize(0));
        assertThat(fromString("    "), hasSize(0));
    }

    @Test
    public void errorsThrowIllegalArgumentExceptions() {
        String[] bads = { "你/好", "text/html; q", "text/html; q=好" };
        for (String bad : bads) {
            try {
                fromString(bad);
                fail(bad + " should have thrown an exception");
            } catch (Exception e) {
                assertThat("Wrong exception type for " + bad, e, instanceOf(IllegalArgumentException.class));
            }

        }
    }

}