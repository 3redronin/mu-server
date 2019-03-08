package io.muserver;


import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

public class ForwardedHeaderTest {

    @Test
    public void nullOrEmptyIsEmptyList() {
        assertThat(parse(null), equalTo(emptyList()));
        assertThat(parse(""), equalTo(emptyList()));
    }

    @Test
    public void canParse() {
        assertThat(parse("for=\"_mdn\""), contains(
            fwd(null, "_mdn", null, null)
        ));

        assertThat(parse("For=\"[2001:db8:cafe::17]:4711\""), contains(
            fwd(null, "[2001:db8:cafe::17]:4711", null, null)
        ));

        assertThat(parse(" for=192.0.2.60;proto=http; by=203.0.113.43 "), contains(
            fwd("203.0.113.43", "192.0.2.60", null, "http")
        ));

        assertThat(parse("for=192.0.2.43, for=198.51.100.17; proto=https"), contains(
            fwd(null, "192.0.2.43", null, null),
            fwd(null, "198.51.100.17", null, "https")
        ));
    }

    @Test
    public void extensionsAllowed() {
        assertThat(parse("umm=hmm"), contains(
            new ForwardedHeader(null, null, null, null, Collections.singletonMap("umm", "hmm"))
        ));
    }

    private static ForwardedHeader fwd(String by, String forValue, String host, String proto) {
        return new ForwardedHeader(by, forValue, host, proto, null);
    }

    private static List<ForwardedHeader> parse(String value) {
        return ForwardedHeader.fromString(value);
    }

}