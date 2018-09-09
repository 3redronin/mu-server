package io.muserver;


import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

public class MultipartRequestBodyParserTest {

    @Test
    public void canDecodeWithPreambleAndEpilogue() throws IOException {
        String input = "blah blah this is ignored preamble\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 7\r\n" +
            "\r\n" +
            "Wor\r\nld\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
            "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
            "\r\n" +
            "你好 the value / with / stuff\r\n" +
            "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n" +
            "this is the epilogue";
        MultipartRequestBodyParser parser = new MultipartRequestBodyParser(null, UTF_8, "2fe110ee-3c8a-480b-a07b-32d777205a76");
        parser.parse(new ByteArrayInputStream(input.getBytes(UTF_8)));

        assertThat(parser.formValue("Hello"), contains("Wor\r\nld"));
        assertThat(parser.formValue("The 你好 name"), contains("你好 the value / with / stuff"));
    }

    @Test
    public void canDecodeWithoutPreambleAndEpilogue() throws IOException {
        String input =
            "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "Content-Disposition: form-data; name=\"Hello\"\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "Wor\r\nld\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
                "\r\n" +
                "你好 the value / with / stuff\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n";
        MultipartRequestBodyParser parser = new MultipartRequestBodyParser(null, UTF_8, "2fe110ee-3c8a-480b-a07b-32d777205a76");
        parser.parse(new ByteArrayInputStream(input.getBytes(UTF_8)));

        assertThat(parser.formValue("Hello"), contains("Wor\r\nld"));
        assertThat(parser.formValue("The 你好 name"), contains("你好 the value / with / stuff"));
    }

    @Test
    public void emptyIsSupported() throws IOException {
        String input = "-----------------------------41184676334--\r\n";
        MultipartRequestBodyParser parser = new MultipartRequestBodyParser(null, UTF_8, "---------------------------41184676334");
        parser.parse(new ByteArrayInputStream(input.getBytes(UTF_8)));
        assertThat(parser.formParams().size(), is(0));
    }

    @Test
    public void filesCanBeUploadedAndTheFilenameIsAddedToTheFormValue() throws IOException {
        String input = "-----------------------------41184676334\r\n" +
            "Content-Disposition: form-data; name=\"再见 \"\r\n" +
            "\r\n" +
            "ha\r\n" +
            "-----------------------------41184676334\r\n" +
            "Content-Disposition: form-data; name=\"blah2\"\r\n" +
            "\r\n" +
            "ha2\r\n" +
            "-----------------------------41184676334\r\n" +
            "Content-Disposition: form-data; name=\"theFile\"; filename=\"a file 文件 & umm yeah! @@@.txt\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "This is a file or 文件 or whatever.\r\n" +
            "-----------------------------41184676334\r\n" +
            "Content-Disposition: form-data; name=\"bla h3  \"\r\n" +
            "\r\n" +
            "ha ha3\r\n" +
            "-----------------------------41184676334\r\n" +
            "Content-Disposition: form-data; name=\"ticked\"\r\n" +
            "\r\n" +
            "on\r\n" +
            "-----------------------------41184676334--\r\n";
        MultipartRequestBodyParser parser = new MultipartRequestBodyParser(new File("target/tempupload/a/b/c/d"), UTF_8, "---------------------------41184676334");
        parser.parse(new ByteArrayInputStream(input.getBytes(UTF_8)));

        assertThat(parser.formValue("再见 "), contains("ha"));
        assertThat(parser.formValue("blah2"), contains("ha2"));
        assertThat(parser.formValue("theFile"), contains("a file 文件 & umm yeah! @@@.txt"));
        assertThat(parser.formValue("bla h3  "), contains("ha ha3"));
        assertThat(parser.formValue("ticked"), contains("on"));

        MuUploadedFile2 theFile = parser.fileParams().get("theFile").get(0);
        assertThat(theFile.filename(), is("a file 文件 & umm yeah! @@@.txt"));
        assertThat(theFile.asString(), is("This is a file or 文件 or whatever."));
        assertThat(theFile.contentType(), is("text/plain"));
        assertThat(theFile.extension(), is("txt"));
        assertThat(theFile.size(), is(37L));

        parser.clean();
    }


}