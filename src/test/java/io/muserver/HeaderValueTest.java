package io.muserver;


import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class HeaderValueTest {

    @Test
    public void canDoSimpleOnes() {
        HeaderValue headerValue = HeaderValue.fromString("image/jpeg").get(0);
        assertThat(headerValue.value(), is("image/jpeg"));
        assertThat(headerValue.parameters().keySet(), is(empty()));
        assertThat(headerValue.toString(), is("image/jpeg"));
    }

    @Test
    public void canReadMultipleOnesSeparatedByCommasUnlessCommaIsInQuotedString() {
        List<HeaderValue> headerValues = HeaderValue.fromString(" one, two; , three;a=\"i have, a comma, or two\" \t\t, four  ;b=\"Also, a comma\"");
        assertThat(headerValues, contains(val("one"), val("two"), val("three", "a", "i have, a comma, or two"),
            val("four", "b", "Also, a comma")));
    }

    @Test
    public void acceptHeadersWork() {
        assertThat(HeaderValue.fromString("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            contains(val("text/html"), val("application/xhtml+xml"),
                val("application/xml", "q", "0.9"),
                val("*/*", "q", "0.8")));

        assertThat(HeaderValue.fromString("text/html, application/xhtml+xml, image/jxr, */*"),
            contains(val("text/html"), val("application/xhtml+xml"),
                val("image/jxr"), val("*/*")));

        assertThat(HeaderValue.fromString("en-GB,en-US;q=0.9,en;q=0.8,zh-CN;q=0.7,zh;q=0.6"),
            contains(val("en-GB"), val("en-US", "q", "0.9"), val("en", "q", "0.8"),
                val("zh-CN", "q", "0.7"), val("zh", "q", "0.6")));
    }

    @Test
    public void contentHeadersWork() {
        assertThat(HeaderValue.fromString("multipart/form-data; boundary=943f35c4-c194-4a64-8291-3164972bac96"),
            contains(val("multipart/form-data","boundary", "943f35c4-c194-4a64-8291-3164972bac96")));
    }

    private static HeaderValue val(String v, String pm, String pv) {
        return new HeaderValue(v, pm, pv);
    }

    private static HeaderValue val(String v) {
        return new HeaderValue(v);
    }

    @Test
    public void canReadParams() {
        HeaderValue headerValue = HeaderValue.fromString("text/plain; charset=UTF-8 ; thingo=bingo ").get(0);
        assertThat(headerValue.value(), is("text/plain"));
        assertThat(headerValue.parameters().get("charset"), is("UTF-8"));
        assertThat(headerValue.parameters().get("thingo"), is("bingo"));
        assertThat(headerValue.toString(), is("text/plain;charset=UTF-8;thingo=bingo"));
    }

    @Test
    public void paramValuesCanBeQuotedStrings() {
        HeaderValue headerValue = HeaderValue.fromString("multipart/mixed; boundary=\"gc0pJq\\\"0M:08jU534;c0p\"; cop=pop").get(0);
        assertThat(headerValue.value(), is("multipart/mixed"));
        assertThat(headerValue.parameters().get("boundary"), is("gc0pJq\"0M:08jU534;c0p"));
        assertThat(headerValue.parameters().get("cop"), is("pop"));
        assertThat(headerValue.toString(), is("multipart/mixed;boundary=\"gc0pJq\\\"0M:08jU534;c0p\";cop=pop"));
    }

    @Test
    public void variousValuesWork() {
        allAre("multipart/mixed;boundary=\"gc0pJq\\\"0M:08jU534;c0p\"",
            "multipart/mixed;boundary=\"gc0pJq\\\"0M:08jU534;c0p\"; ",
            "multipart/mixed; boundary=\"gc0pJq\\\"0M:08jU534;c0p\";",
            "multipart/mixed; boundary=\"gc0pJq\\\"0M:08jU534;c0p\"",
            "multipart/mixed; boundary=\"gc0pJq\\\"0M:08jU534;c0p\"  \t      ",
            "multipart/mixed;boundary=\"gc0pJq\\\"0M:08jU534;c0p\"",
            "multipart/mixed;\tboundary=\"gc0pJq\\\"0M:08jU534;c0p\"\t"
        );
    }

    private void allAre(String expected, String... inputs) {
        for (String input : inputs) {
            assertThat(input, HeaderValue.fromString(input).get(0).toString(), equalTo(expected));
        }
    }

    @Test
    public void byteAndCharMethodsAreTheSame() {
        for (int i = 0; i < Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            char c = (char) i;
            assertThat("isTChar " + i, Parser.isTChar(b), equalTo(Parser.isTChar(c)));
            assertThat("isOWS " + i, Parser.isOWS(b), equalTo(Parser.isOWS(c)));
            assertThat("isVChar " + i, Parser.isVChar(b), equalTo(Parser.isVChar(c)));
        }
    }
}