package io.muserver.rest;

import org.junit.Test;

import static io.muserver.rest.Jaxutils.leniantUrlDecode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class JaxutilsTest {

    @Test
    public void decodesOctetsButAllowsOtherThingsIn() {
        assertThat(leniantUrlDecode("Hello%20 world %% % %2F %2G"),
            equalTo("Hello  world %% % / %2G"));
    }

}