package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.ws.rs.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MultipartFormParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canParseEmptyBodies(String bufferSize) throws Throwable {
        var boundary = UUID.randomUUID().toString();
        var input = "--" + boundary + "--";
        var result = parse(boundary, input, bufferSize);
        assertThat(result.size(), equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void readingAfterEndNotAllowed(String bufferSize) throws Exception {
        var boundary = UUID.randomUUID().toString();
        var input = "--" + boundary + "--hi";
        assertThrows(BadRequestException.class, () -> parse(boundary, input, bufferSize));
    }


    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canParseSingleText(String bufferSize) throws Throwable {
        var input = """
            --boundary
            Content-Disposition: form-data; name="text_field"
                        
            This is a text value.
            --boundary--
            """;

        var result = parse("boundary", input, bufferSize);
        assertThat(result.get("text_field"), equalTo("This is a text value"));
    }

    /*

    single file upload

--boundary
Content-Disposition: form-data; name="file_field"; filename="example.txt"
Content-Type: text/plain

Contents of the text file go here.
--boundary--


multiple fields
--boundary
Content-Disposition: form-data; name="text_field"

This is a text value.
--boundary
Content-Disposition: form-data; name="file_field"; filename="image.jpg"
Content-Type: image/jpeg

[Binary image data goes here]
--boundary--



multiple file uploads
--boundary
Content-Disposition: form-data; name="file_1"; filename="document.pdf"
Content-Type: application/pdf

[Binary PDF data goes here]
--boundary
Content-Disposition: form-data; name="file_2"; filename="image.png"
Content-Type: image/png

[Binary image data goes here]
--boundary--



similar boundary 1
--boundary
Content-Disposition: form-data; name="text_field"

This is a text value with a similar boundary-like string: -----------------1234567890
--boundary--


similar boundary 2
--boundary
Content-Disposition: form-data; name="text_field"

This is a text value with a boundary-like string: ---------------------------1234567890
--boundary--


file with boundary inside
--boundary
Content-Disposition: form-data; name="file_field"; filename="file----boundary.txt"
Content-Type: text/plain

Contents of the text file with boundary---- inside go here.
--boundary--




     */


    private MultipartFormParser parse(String boundary, String input, String bufferSize) throws Throwable {
        var parser = new MultipartFormParser(Files.createTempDirectory("muservertests"), StandardCharsets.UTF_8, boundary);
        var buffer = Mutils.toByteBuffer(input.replace("\n", "\r\n"));
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

    private static void processAndWaitForBuffer(MultipartFormParser parser, ByteBuffer buffer) throws Throwable {
        var result = new CompletableFuture<Void>();
        DoneCallback callback = error -> {
            if (error == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(error);
            }
        };
        parser.onDataReceived(buffer, callback);
        try {
            result.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

}