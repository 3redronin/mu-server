package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static io.muserver.MultipartFormParserTest.processAndWaitForBuffer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class UrlEncodedFormReaderTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canReadStuffProperly(String type) throws Throwable {
        UrlEncodedFormReader result = parse(type, "The+%E4%BD%A0%E5%A5%BD+%22name%22+%3A+%3Chi%3E+%5Cr%5Cn=The+%E4%BD%A0%E5%A5%BD+%22value%22+%3A+%3Chi%3E+%5Cr%5Cn");
        assertThat(result.getAll("The 你好 \"name\" : <hi> \\r\\n"), contains("The 你好 \"value\" : <hi> \\r\\n"));
    }


    private UrlEncodedFormReader parse(String bufferSize, String input) throws Throwable {
        var buffer = Mutils.toByteBuffer(input.trim().replace("\n", "\r\n"));
        return parse(bufferSize, buffer);
    }

    @NotNull
    private static UrlEncodedFormReader parse(String bufferSize, ByteBuffer buffer) throws Throwable {
        var parser = new UrlEncodedFormReader(100000);
        if (bufferSize.equals("*")) {
            processAndWaitForBuffer(parser, buffer);
        } else {
            int size = Integer.parseInt(bufferSize);
            int remaining = buffer.remaining();
            while (remaining > 0) {
                int chunkSize = Math.min(size, remaining);
                ByteBuffer subBuffer = buffer.duplicate();
                subBuffer.limit(subBuffer.position() + chunkSize);
                buffer.position(buffer.position() + chunkSize);
                remaining -= chunkSize;
                processAndWaitForBuffer(parser, subBuffer);
            }
        }
        parser.onComplete();
        return parser;
    }


}