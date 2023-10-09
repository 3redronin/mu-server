package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MuHeadersTest {

    private final MuHeaders headers = new MuHeaders();

    @Test
    public void itsCaseInsensitive() {
        headers.set("test", "value");
        assertThat(headers.contains("test"), is(true));
        assertThat(headers.contains("TEST"), is(true));
        assertThat(headers.contains("tEsT"), is(true));
        assertThat(headers.get("test"), equalTo("value"));
        assertThat(headers.get("TEsT"), equalTo("value"));
        assertThat(headers.getAll("TesT"), contains("value"));
        assertThat(headers.toString(), equalTo("MuHeaders[test: value]"));
    }

}