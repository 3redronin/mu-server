package io.muserver;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class MultipartParserTest {

    @Test
    public void canParse() throws IOException, InvalidRequestException {

        String boundary = "943f35c4-c194-4a64-8291-3164972bac96";
        byte[] bytes = ("--943f35c4-c194-4a64-8291-3164972bac96\r\n" +
            "Content-Disposition: form-data; name=\"Hello\"\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "World\r\n" +
            "--943f35c4-c194-4a64-8291-3164972bac96\r\n" +
            "Content-Disposition: form-data; name=\"The name\"\r\n" +
            "Content-Length: 24\r\n" +
            "\r\n" +
            "the value / with / stuff\r\n" +
            "--943f35c4-c194-4a64-8291-3164972bac96--\r\n").getBytes(StandardCharsets.US_ASCII);
        InputStream is = new ByteArrayInputStream(bytes);

        MultipartParser multipartParser = new MultipartParser(boundary);
        RequestParameters params = multipartParser.parse(is);
        assertThat(params.get("Hello"), equalTo("World"));
        assertThat(params.get("The name"), equalTo("the value / with / stuff"));
    }

}