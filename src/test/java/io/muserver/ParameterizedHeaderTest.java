package io.muserver;

import org.junit.Test;

import static io.muserver.ParameterizedHeader.fromString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.fail;

public class ParameterizedHeaderTest {

    @Test
    public void valueOnlyIsSupported() {
        ParameterizedHeader v = fromString("no-cache");
        assertThat(v.parameters().entrySet(), hasSize(1));
        assertThat(v.parameter("no-cache"), is(nullValue()));
        assertThat(v.hasParameter("no-cache"), is(true));
        assertThat(v.parameterNames(), contains("no-cache"));

        assertThat(v.hasParameter("something-else"), is(false));
    }

    @Test
    public void multipleValuesSupported() {
        ParameterizedHeader v = fromString("no-cache , z-cache=\" I'm a big ol' \\\"quoted string\\\"\", a-cache=blah");
        assertThat(v.parameters().entrySet(), hasSize(3));
        assertThat(v.hasParameter("no-cache"), is(true));
        assertThat(v.hasParameter("z-cache"), is(true));
        assertThat(v.hasParameter("a-cache"), is(true));
        assertThat(v.parameterNames(), contains("no-cache", "z-cache", "a-cache"));
        assertThat(v.parameter("a-cache"), is("blah"));
        assertThat(v.parameter("a-cache", "meh"), is("blah"));
        assertThat(v.parameter("z-cache"), is(" I'm a big ol' \"quoted string\""));
    }

    @Test
    public void emptyNullAndBlankStringReturnsEmptyMap() {
        assertThat(fromString(null).parameters().entrySet(), hasSize(0));
        assertThat(fromString("").parameters().entrySet(), hasSize(0));
        assertThat(fromString("     ").parameters().entrySet(), hasSize(0));
    }

    @Test
    public void errorsThrowIllegalArgumentExceptions() {
        String[] bads = { "你/好", "text/html; q", "text/html; q=好", "badly-quoted-boy=I'm \" bad at quoting", "badly-quoted-boy=\"I'm \" bad at quoting\"" };
        for (String bad : bads) {
            try {
                ParameterizedHeader parameterizedHeader = fromString(bad);
                fail(bad + " should have thrown an exception but was " + parameterizedHeader);
            } catch (Exception e) {
                assertThat("Wrong exception type for " + bad, e, instanceOf(IllegalArgumentException.class));
            }

        }
    }

}