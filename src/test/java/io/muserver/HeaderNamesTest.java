package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

class HeaderNamesTest {

    @Test
    void allBuiltInAreLowercase() {
        for (Map.Entry<CharSequence, HeaderString> entry : HeaderNames.builtIn.entrySet()) {
            var name = entry.getKey().toString();
            assertThat(name.toLowerCase(), equalTo(name));
            HeaderString hs = entry.getValue();
            assertThat(hs.toString(), sameInstance(name));
        }
    }

    @Test
    void lookupsGiveSameInstances() {
        var lookedUpByString = HeaderNames.findBuiltIn("ConTent-Type");
        var lookedUpByStringBuilder = HeaderNames.findBuiltIn(new StringBuilder("content-type"));
        var lookedUpByStringHeaderStringValueOf = HeaderString.valueOf("CONTENT-TYPE", HeaderString.Type.HEADER);
        var constant = HeaderNames.CONTENT_TYPE;
        assertThat(lookedUpByString, sameInstance(constant));
        assertThat(lookedUpByStringBuilder, sameInstance(constant));
        assertThat(lookedUpByStringHeaderStringValueOf, sameInstance(constant));
    }

}