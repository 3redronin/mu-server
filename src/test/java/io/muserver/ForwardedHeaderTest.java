package io.muserver;


import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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

    @Test
    public void getterWork() {
        ForwardedHeader fwd = new ForwardedHeader("by", "for", "host", "proto", Collections.singletonMap("Hi", "World"));
        assertThat(fwd.by(), is("by"));
        assertThat(fwd.forValue(), is("for"));
        assertThat(fwd.host(), is("host"));
        assertThat(fwd.proto(), is("proto"));
        assertThat(fwd.extensions(), is(Collections.singletonMap("Hi", "World")));
    }

    @Test
    public void canRoundTrip() {
        Map<String, String> extensions = new LinkedHashMap<>();
        extensions.put("secret", "9823748923748937");
        extensions.put("Hello", "Oh hi, world");
        List<ForwardedHeader> original = asList(fwd("192.156.0.0", "10.10.10.10", "example.org", "https"),
            new ForwardedHeader(null, "1.2.3.4", "machine.example.org", "http", extensions));

        String asString = ForwardedHeader.toString(original);
        assertThat(asString, equalTo("by=192.156.0.0;for=10.10.10.10;host=example.org;proto=https, " +
            "for=1.2.3.4;host=machine.example.org;proto=http;secret=9823748923748937;Hello=\"Oh hi, world\""));

        List<ForwardedHeader> recreated = ForwardedHeader.fromString(asString);
        assertThat(original, equalTo(recreated));
    }

    @Test
    public void canRoundTripWithQuotedStrings() {
        ForwardedHeader fwd = fwd(null, null, "example.org:9090", null);
        String asString = fwd.toString();
        assertThat(asString, equalTo("host=\"example.org:9090\""));
        List<ForwardedHeader> forwardedHeaders = ForwardedHeader.fromString(asString);
        assertThat(forwardedHeaders, contains(fwd));
        assertThat(forwardedHeaders.get(0).host(), equalTo("example.org:9090"));
    }

    @Test
    public void canHaveColonsInHostNames() {
        List<ForwardedHeader> forwardedHeaders = ForwardedHeader.fromString("for=10.10.0.10;proto=https;host=host.example.org:8000;by=127.0.0.1");
        assertThat(forwardedHeaders, hasSize(1));
        ForwardedHeader fwd = forwardedHeaders.get(0);
        assertThat(fwd.forValue(), is("10.10.0.10"));
        assertThat(fwd.proto(), is("https"));
        assertThat(fwd.host(), is("host.example.org:8000"));
        assertThat(fwd.by(), is("127.0.0.1"));
    }

    static ForwardedHeader fwd(String by, String forValue, String host, String proto) {
        return new ForwardedHeader(by, forValue, host, proto, null);
    }

    private static List<ForwardedHeader> parse(String value) {
        return ForwardedHeader.fromString(value);
    }

}