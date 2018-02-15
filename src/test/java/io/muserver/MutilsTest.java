package io.muserver;

import org.junit.Test;

import static io.muserver.Mutils.join;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class MutilsTest {

    @Test
    public void joinJoinsThingsToBeJoined() {
        assertThat(join("/sample-static/", "/", "/something.txt"), equalTo("/sample-static/something.txt"));
        assertThat(join("/sample-static", "/", "/something.txt"), equalTo("/sample-static/something.txt"));
        assertThat(join("/sample-static", "/", "something.txt"), equalTo("/sample-static/something.txt"));
    }

    @Test
    public void canTrim() {
        assertThat(Mutils.trim("/sam/ple/", "/"), equalTo("sam/ple"));
        assertThat(Mutils.trim("sam/ple/", "/"), equalTo("sam/ple"));
        assertThat(Mutils.trim("/sam/ple", "/"), equalTo("sam/ple"));
        assertThat(Mutils.trim("sam/ple", "/"), equalTo("sam/ple"));
    }

}