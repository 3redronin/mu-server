package io.muserver;

import org.junit.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.format.DateTimeParseException;
import java.util.Date;

import static io.muserver.Mutils.join;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class MutilsTest {

    @Test
    public void unreservedCharactersAreNotUrlEncodedAsPerRFC3986() {
        assertThat(Mutils.urlEncode("Aa09-._~"), equalTo("Aa09-._~"));
    }

    @Test
    public void unreservedCharactersCanBeDecoded() {
        assertThat(Mutils.urlDecode("A%41-%2D.%2E_%5F~%7E"), equalTo("AA--..__~~"));
    }

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

    @Test
    public void parsesDatesCorrectly() {
        assertThat(Mutils.fromHttpDate("Sat, 28 Jul 2018 13:50:55 GMT"), equalTo(new Date(1532785855000L)));
        assertThat(Mutils.fromHttpDate("Fri, 2 Aug 2019 23:17:35 GMT"), equalTo(new Date(1564787855000L)));
    }

    @Test(expected = DateTimeParseException.class)
    public void throwsIfBadFormat() {
        Mutils.fromHttpDate("28Jul 2018 13:50:55");
    }

    @Test(expected = IllegalArgumentException.class)
    public void toByteBufferThrowsIfTextNull() {
        Mutils.toByteBuffer(null);
    }

    @Test
    public void toByteBufferWithEmptyStringWorks() {
        ByteBuffer bb = Mutils.toByteBuffer("");
        assertThat(bb.remaining(), is(0));
    }

    @Test
    public void toByteBufferWithNonEmptyStringWorks() {
        ByteBuffer bb = Mutils.toByteBuffer("Hello world");
        assertThat(bb.remaining(), is(11));
    }

    @Test
    public void pathAndQueryWorks() {
        URI base = URI.create("https://example.org");
        assertThat(Mutils.pathAndQuery(base), equalTo(""));
        assertThat(Mutils.pathAndQuery(base.resolve("/")), equalTo("/"));
        assertThat(Mutils.pathAndQuery(base.resolve("/blah%20%2Fblah")), equalTo("/blah%20%2Fblah"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah/")), equalTo("/~blah+blah/"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah;matrix=yeah/blah")), equalTo("/~blah+blah;matrix=yeah/blah"));
        assertThat(Mutils.pathAndQuery(base.resolve("?")), equalTo("?"));
        assertThat(Mutils.pathAndQuery(base.resolve("/?")), equalTo("/?"));
        assertThat(Mutils.pathAndQuery(base.resolve("/blah%20%2Fblah?")), equalTo("/blah%20%2Fblah?"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah/?")), equalTo("/~blah+blah/?"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah;matrix=yeah/blah?")), equalTo("/~blah+blah;matrix=yeah/blah?"));
        assertThat(Mutils.pathAndQuery(base.resolve("?a%20key=a%20value&another=something%20else")), equalTo("?a%20key=a%20value&another=something%20else"));
        assertThat(Mutils.pathAndQuery(base.resolve("/?a%20key=a%20value&another=something%20else")), equalTo("/?a%20key=a%20value&another=something%20else"));
        assertThat(Mutils.pathAndQuery(base.resolve("/blah%20%2Fblah?a%20key=a%20value&another=something%20else")), equalTo("/blah%20%2Fblah?a%20key=a%20value&another=something%20else"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah/?a%20key=a%20value&another=something%20else")), equalTo("/~blah+blah/?a%20key=a%20value&another=something%20else"));
        assertThat(Mutils.pathAndQuery(base.resolve("/~blah+blah;matrix=yeah/blah?a%20key=a%20value&another=something%20else")), equalTo("/~blah+blah;matrix=yeah/blah?a%20key=a%20value&another=something%20else"));
    }

}