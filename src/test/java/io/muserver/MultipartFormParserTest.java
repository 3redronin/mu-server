package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.ws.rs.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MultipartFormParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canParseEmptyBodies(String bufferSize) throws Throwable {
        var boundary = UUID.randomUUID().toString();
        var input = "--" + boundary + "--";
        var result = parse(boundary, bufferSize, input);
        assertThat(result.size(), equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void readingAfterEndNotAllowed(String bufferSize) throws Exception {
        var boundary = UUID.randomUUID().toString();
        var input = "--" + boundary + "--hi";
        assertThrows(BadRequestException.class, () -> parse(boundary, bufferSize, input));
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

        var result = parse("boundary", bufferSize, input);
        assertThat(result.get("text_field"), equalTo("This is a text value."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canParseMultipleText(String bufferSize) throws Throwable {
        var input = """
            --boundary
            Content-Disposition: form-data; name="hello"
            content-type: text/plain;charset=us-ascii
                        
            hello
            you
            --boundary
            content-disposition: form-data; name="hello"
            
            你好
            --boundary
            content-disposition: form-data; name="goodbye you"
            ignored-header: ignored-value
            content-type: text/plain
            
            bye bye -- 再见
            --boundary--
            """;

        var result = parse("boundary", bufferSize, input);
        assertThat(result.getAll("hello"), containsInAnyOrder("hello\r\nyou", "你好"));
        assertThat(result.getAll("goodbye you"), contains("bye bye -- 再见"));
    }


    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void canParseSingleAsciiTextFile(String bufferSize) throws Throwable {
        var input = """
            --boundary
            Content-Disposition: form-data; name="hello"; filename="my file.txt"
            content-type: text/plain;charset=us-ascii
                        
            hello
            you
            --boundary--
            """;

        var result = parse("boundary", bufferSize, input);
        assertThat(result.getAll("hello"), empty());
        assertThat(result.uploadedFiles().entrySet(), hasSize(1));
        var uploadedFile = result.uploadedFile("hello");
        assertThat(uploadedFile, not(nullValue()));
        assertThat(uploadedFile.filename(), equalTo("my file.txt"));
        assertThat(uploadedFile.contentType(), equalTo("text/plain;charset=us-ascii"));
        assertThat(uploadedFile.extension(), equalTo("txt"));
        assertThat(uploadedFile.asFile().isFile(), equalTo(true));
        assertThat(Files.exists(uploadedFile.asPath()), equalTo(true));
        assertThat(uploadedFile.asString(), equalTo("hello\r\nyou"));
        assertThat(uploadedFile.size(), equalTo(10L));
    }


    @ParameterizedTest
    @ValueSource(strings = {"1", "*"})
    public void individualSectionsCanHaveTheirOwnCharsets(String bufferSize) throws Throwable {
        var input = """
            --boundary00000000000000000000000000000000000000123
            Content-Disposition: form-data; name="hello"
            content-type: text/plain;charset=utf-16
                        
            tobereplaced
            --boundary00000000000000000000000000000000000000123
            Content-Disposition: form-data; name="hello"
                        
            hello you
            --boundary00000000000000000000000000000000000000123--
            """.trim().replace("\n", "\r\n").split("tobereplaced");

        var baos = new ByteArrayOutputStream();
        baos.writeBytes(input[0].getBytes(StandardCharsets.UTF_8));
        baos.writeBytes("Hello in utf-16".getBytes(StandardCharsets.UTF_16));
        baos.writeBytes(input[1].getBytes(StandardCharsets.UTF_8));

        var result = parse("boundary00000000000000000000000000000000000000123", bufferSize, ByteBuffer.wrap(baos.toByteArray()));
        assertThat(result.getAll("hello"), containsInAnyOrder("hello you", "Hello in utf-16"));
    }



    /*

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


    private MultipartFormParser parse(String boundary, String bufferSize, String input) throws Throwable {
        var buffer = Mutils.toByteBuffer(input.trim().replace("\n", "\r\n"));
        return parse(boundary, bufferSize, buffer);
    }

    @NotNull
    private static MultipartFormParser parse(String boundary, String bufferSize, ByteBuffer buffer) throws Throwable {
        var parser = new MultipartFormParser(Files.createTempDirectory("muservertests"), StandardCharsets.UTF_8, boundary);
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