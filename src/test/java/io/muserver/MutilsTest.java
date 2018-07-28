package io.muserver;

import org.junit.Test;

import java.util.Date;

import static io.muserver.Mutils.join;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

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

    @Test
    public void canHtmlEncode() {
        assertThat(Mutils.htmlEncode("<b>It's me, a developer/\"engineer\" and I want to say 你好 & stuff</b>"),
            is("&lt;b&gt;It&#x27;s me, a developer&#x2F;&quot;engineer&quot; and I want to say 你好 &amp; stuff&lt;&#x2F;b&gt;"));
    }

    @Test
    public void formatsDatesCorrectly() {
        assertThat(Mutils.toHttpDate(new Date(1532785855376L)), equalTo("Sat, 28 Jul 2018 13:50:55 GMT"));
        assertThat(Mutils.toHttpDate(new Date(1564787855376L)), equalTo("Fri, 2 Aug 2019 23:17:35 GMT"));
    }

}