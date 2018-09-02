package io.muserver;


import org.junit.Test;

import javax.ws.rs.core.MediaType;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class MediaTypeParserTest {

    @Test
    public void canDoSimpleOnes() {
        MediaType mediaType = MediaTypeParser.fromString("image/jpeg");
        assertThat(mediaType.getType(), is("image"));
        assertThat(mediaType.getSubtype(), is("jpeg"));
        assertThat(mediaType.getParameters().keySet(), is(empty()));
        assertThat(MediaTypeParser.toString(mediaType), is("image/jpeg"));
    }

    @Test
    public void canReadParams() {
        MediaType mediaType = MediaTypeParser.fromString("text/plain; charset=UTF-8 ; thingo=bingo ");
        assertThat(mediaType.getType(), is("text"));
        assertThat(mediaType.getSubtype(), is("plain"));
        assertThat(mediaType.getParameters().get("charset"), is("UTF-8"));
        assertThat(mediaType.getParameters().get("thingo"), is("bingo"));
        assertThat(MediaTypeParser.toString(mediaType), is("text/plain;charset=UTF-8;thingo=bingo"));
    }

    @Test
    public void paramValuesCanBeQuotedStrings() {
        MediaType mediaType = MediaTypeParser.fromString("multipart/mixed; boundary=\"gc0pJq\\\"0M:08jU534;c0p\"; cop=pop");
        assertThat(mediaType.getType(), is("multipart"));
        assertThat(mediaType.getSubtype(), is("mixed"));
        assertThat(mediaType.getParameters().get("boundary"), is("gc0pJq\"0M:08jU534;c0p"));
        assertThat(mediaType.getParameters().get("cop"), is("pop"));
        assertThat(MediaTypeParser.toString(mediaType), is("multipart/mixed;boundary=\"gc0pJq\\\"0M:08jU534;c0p\";cop=pop"));
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
            assertThat(input, MediaTypeParser.toString(MediaTypeParser.fromString(input)), equalTo(expected));
        }
    }

}