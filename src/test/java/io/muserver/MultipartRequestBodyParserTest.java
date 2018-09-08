package io.muserver;


import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class MultipartRequestBodyParserTest {

    @Test
    public void canDecode() throws IOException {
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
            "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n";


        byte[] bytes = input.getBytes(UTF_8);
        MultipartRequestBodyParser parser = new MultipartRequestBodyParser(UTF_8, "2fe110ee-3c8a-480b-a07b-32d777205a76");
        parser.parse(new ByteArrayInputStream(bytes));

        assertThat(parser.formValue("Hello"), contains("Wor\r\nld"));
        assertThat(parser.formValue("The 你好 name"), contains("你好 the value / with / stuff"));
    }
    
}