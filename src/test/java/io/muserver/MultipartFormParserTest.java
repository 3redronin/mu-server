package io.muserver;

import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MultipartFormParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"one-by-one", "full"})
    public void emptyBodiesSupported(String type) throws IOException {
        var inputStream = getInput(type,
            "-----------------------------40328356438088973481959884063--\r\n");
        var parser = new MultipartFormParser(tempDir(),
            "---------------------------40328356438088973481959884063", inputStream, 8192, StandardCharsets.UTF_8);
        parser.discardPreamble();
        assertThat(parser.readPartHeaders(), nullValue());
        parser.discardEpilogue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"one-by-one", "full"})
    public void preambleAndEpilogueIsIgnored(String type) throws IOException {
        var inputStream = getInput(type,
            "Hi there. Welcome to the preamble\r\n-----------------------------40328356438088973481959884063--\r\nThis is the epilogue.");
        var parser = new MultipartFormParser(tempDir(),
            "---------------------------40328356438088973481959884063", inputStream, 8192, StandardCharsets.UTF_8);
        parser.discardPreamble();
        assertThat(parser.readPartHeaders(), nullValue());
        parser.discardEpilogue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"one-by-one", "full"})
    public void linearWhiteSpaceCharsAfterBoundaryIsAllowed(String type) throws IOException {
        var inputStream = getInput(type,
            "Hi there. Welcome to the preamble\r\n-----------------------------40328356438088973481959884063-- \t \r\nThis is the epilogue.");
        var parser = new MultipartFormParser(tempDir(),
            "---------------------------40328356438088973481959884063", inputStream, 8192, StandardCharsets.UTF_8);
        parser.discardPreamble();
        assertThat(parser.readPartHeaders(), nullValue());
        parser.discardEpilogue();
    }

    /*
    TODO alternative empty part
    --boundary\r\n
--boundary--\r\n
     */


    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void simpleBodiesSupported(String type) throws IOException {
        var inputStream = getInput(type,
            " This is the preamble.  It is to be ignored, though it\n" +
                "is a handy place for composition agents to include an\n" +
                "explanatory note to non-MIME conformant readers.\n" +
                "\r\n" +
                "--simple boundary\r\n" +
                "\r\n" +
                "This is implicitly typed plain US-ASCII text.\n" +
                "It does NOT end with a linebreak.\r\n" +
                "--simple boundary\r\n" +
                "Content-type: text/plain; charset=us-ascii\r\n" +
                "\r\n" +
                "This is explicitly typed plain US-ASCII text.\n" +
                "It DOES end with a linebreak.\r\n" +
                "\r\n" +
                "--simple boundary--\r\n" +
                "\r\n" +
                "This is the epilogue.  It is also to be ignored.");
        var parser = new MultipartFormParser(tempDir(),"simple boundary", inputStream, 8192, StandardCharsets.UTF_8);
        parser.discardPreamble();
        Headers part1Headers = parser.readPartHeaders();
        assertThat(part1Headers, notNullValue());
        assertThat(part1Headers.size(), equalTo(0));

        var part1Body = parser.readString(StandardCharsets.US_ASCII);
        assertThat(part1Body, equalTo("This is implicitly typed plain US-ASCII text.\nIt does NOT end with a linebreak."));

        Headers part2Headers = parser.readPartHeaders();
        assertThat(part2Headers, notNullValue());
        assertThat(part2Headers.size(), equalTo(1));
        assertThat(part2Headers.contentType(), equalTo(MediaType.valueOf("text/plain; charset=us-ascii")));
        var part2Body = parser.readString(StandardCharsets.UTF_8);
        assertThat(part2Body, equalTo("This is explicitly typed plain US-ASCII text.\nIt DOES end with a linebreak.\r\n"));

        assertThat(parser.readPartHeaders(), nullValue());

        parser.discardEpilogue();
    }


    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void theEncodingOfPartsCanBeSpecifiedWith_charset_Value(String type) throws IOException {
        var inputStream = getInput(type, "------WebKitFormBoundary688vjJHOokza5SAR\r\n" +
                "Content-Disposition: form-data; name=\"_charset_\"\r\n" +
                "\r\n" +
                "ISO-8859-5\r\n" +
                "------WebKitFormBoundary688vjJHOokza5SAR\r\n" +
                "Content-Disposition: form-data; name=\"inputBox\"\r\n" +
                "\r\n" +
                "ЧАСТЬ ПЕРВАЯ.\r\n" +
                "------WebKitFormBoundary688vjJHOokza5SAR--", Charset.forName("ISO-8859-5"));
        var parser = new MultipartFormParser(tempDir(),"----WebKitFormBoundary688vjJHOokza5SAR", inputStream, 8192, StandardCharsets.UTF_8);
        var form = parser.parseFully();
        assertThat(form.getAll("_charset_"), contains("ISO-8859-5"));
        assertThat(form.getAll("inputBox"), contains("ЧАСТЬ ПЕРВАЯ."));
        assertThat(form.all().entrySet(), hasSize(2));
    }


    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void browserStyleEmptiesAreEmpty(String type) throws IOException {
        var boundary = UUID.randomUUID().toString();
        var inputStream = getInput(type, "--" + boundary + "--");
        var form = new MultipartFormParser(tempDir(),boundary, inputStream, 8192, StandardCharsets.UTF_8).parseFully();
        assertThat(form.all().entrySet(), empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void aSinglePartWithNoHeadersAndEmptyBodyReturnsEmptyForm(String type) throws IOException {
        var boundary = UUID.randomUUID().toString();
        var inputStream = getInput(type, "--" + boundary + "\r\n\r\n\r\n--" + boundary + "--");
        var form = new MultipartFormParser(tempDir(),boundary, inputStream, 8192, StandardCharsets.UTF_8).parseFully();
        assertThat(form.all().entrySet(), empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    @Disabled("Not yet supported")
    public void partWithNoBodyReturnsEmptyForm(String type) throws IOException {
        var boundary = UUID.randomUUID().toString();
        var inputStream = getInput(type, "--" + boundary + "\r\n\r\n--" + boundary + "--");
        var form = new MultipartFormParser(tempDir(),boundary, inputStream, 8192, StandardCharsets.UTF_8).parseFully();
        assertThat(form.all().entrySet(), empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void singleTextSupported(String type) throws IOException {
        var form = multipartForm(type, "--boundary\r\n" +
                "Content-Disposition: form-data; name=\"text_field\"\r\n" +
                "\r\n" +
                "This is a text value.\r\n" +
                "--boundary--");
        assertThat(form.getAll("text_field"), contains("This is a text value."));
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void multipleValuesSupported(String type) throws IOException {
        var form = multipartForm(type, "--boundary\r\n" +
                "Content-Disposition: form-data; name=\"hello\"\r\n" +
                "content-type: text/plain;charset=us-ascii\r\n" +
                "\r\n" +
                "hello\r\n" +
                "you\r\n" +
                "--boundary\r\n" +
                "content-disposition: form-data; name=\"hello\"\r\n" +
                "\r\n" +
                "你好\r\n" +
                "--boundary\r\n" +
                "content-disposition: form-data; name=\"goodbye you\"\r\n" +
                "ignored-header: ignored-value\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n" +
                "bye bye -- 再见\r\n" +
                "--boundary--\r\n");
        assertThat(form.getAll("hello"), contains("hello\r\nyou", "你好"));
        assertThat(form.getAll("goodbye you"), contains("bye bye -- 再见"));
    }
    
    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void canDecodeWithPreambleAndEpilogue(String type) throws IOException {
        var form = multipartForm(type, "blah blah this is ignored preamble\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a7 nope\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "Content-Disposition: form-data; name=\"Hello\"\r\n" +
                "Content-Length: 7\r\n" +
                "\r\n" +
                "Wor\r\n" +
                "ld\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "Content-Disposition: form-data; no-name=\"Hello\"\r\n" +
                "\r\n" +
                "This is just an ignored section\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "\r\n" +
                "This is just an ignored section\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76\r\n" +
                "Content-Disposition: form-data; name=\"The 你好 name\"\r\n" +
                "\r\n" +
                "你好 the value / with / stuff\r\n" +
                "--2fe110ee-3c8a-480b-a07b-32d777205a76--\r\n" +
                "this is the epilogue", "2fe110ee-3c8a-480b-a07b-32d777205a76");
        assertThat(form.getAll("Hello"), contains("Wor\r\nld"));
        assertThat(form.getAll("The 你好 name"), contains("你好 the value / with / stuff"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void formNamesCanBeUTF8(String type) throws IOException {
        var form = multipartForm(type, "------WebKitFormBoundaryr1H5MRBBwYhyzO4H\r\n" +
                "Content-Disposition: form-data; name=\"The 你好 %22name%22 : <hi> \"\r\n" +
                "\r\n" +
                "The 你好 \"value\" : <hi> \\r\\n\r\n" +
                "------WebKitFormBoundaryr1H5MRBBwYhyzO4H--\r\n", "----WebKitFormBoundaryr1H5MRBBwYhyzO4H");
        assertThat(form.getAll("The 你好 \"name\" : <hi> "), contains("The 你好 \"value\" : <hi> \\r\\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void simpleTextFilesCanBeUploaded(String type) throws IOException {
        var form = multipartForm(type, "--boundary\r\n" +
                "Content-Disposition: form-data; name=\"hello\"; filename=\"my file.txt\"\r\n" +
                "content-type: text/plain;charset=us-ascii\r\n" +
                "\r\n" +
                "hello\r\n" +
                "you\r\n" +
                "--boundary--\r\n", "boundary");
        assertThat(form.all().entrySet(), empty());
        assertThat(form.uploadedFiles().entrySet(), hasSize(1));
        var file = form.uploadedFile("hello");
        assertThat(file, notNullValue());
        assertThat(file.filename(), equalTo("my file.txt"));
        assertThat(file.contentType(), equalTo("text/plain;charset=us-ascii"));
        assertThat(file.asString(), equalTo("hello\r\nyou"));
        assertThat(file.size(), equalTo(10L));
        assertThat(file.extension(), equalTo("txt"));
        assertThat(file.asBytes(), equalTo("hello\r\nyou".getBytes(StandardCharsets.US_ASCII)));
        assertThat(file.asFile().isFile(), equalTo(true));
        assertThat(file.asPath().toFile().isFile(), equalTo(true));
        var newDest = tempDir().resolve("saved" + UUID.randomUUID() + ".txt");
        file.saveTo(newDest);
        assertThat(Files.isRegularFile(newDest), equalTo(true));
        assertThat(Files.readString(newDest, StandardCharsets.US_ASCII), equalTo("hello\r\nyou"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void individualSectionsCanHaveTheirOwnCharsets(String type) throws Throwable {
        var input = ("--boundary00000000000000000000000000000000000000123\r\n" +
                "Content-Disposition: form-data; name=\"hello\"\r\n" +
                "content-type: text/plain;charset=utf-16\r\n" +
                "\r\n" +
                "tobereplaced\r\n" +
                "--boundary00000000000000000000000000000000000000123\r\n" +
                "Content-Disposition: form-data; name=\"hello\"\r\n" +
                "\r\n" +
                "hello you\r\n" +
                "--boundary00000000000000000000000000000000000000123--\r\n"
        ).split("tobereplaced");

        var baos = new ByteArrayOutputStream();
        baos.writeBytes(input[0].getBytes(StandardCharsets.UTF_8));
        baos.writeBytes("Hello in utf-16".getBytes(StandardCharsets.UTF_16));
        baos.writeBytes(input[1].getBytes(StandardCharsets.UTF_8));

        try (var inputStream = inputStream(type, baos.toByteArray())) {
            var parser = new MultipartFormParser(tempDir(), "boundary00000000000000000000000000000000000000123", inputStream, 100, StandardCharsets.UTF_8);
            var form = parser.parseFully();
            assertThat(form.getAll("hello"), contains("Hello in utf-16", "hello you"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void mulitpleFieldsCanBeUploaded(String type) throws Throwable {
        var input = ("--boundary00000000000000000000000000000000000000123\r\n" +
            "Content-Disposition: form-data; name=\"image\"; filename=\"/tmp/广州, china <>:?\\\"|*.jpeg\"\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "\r\n" +
            "Binary image data goes here\r\n" +
            "--boundary00000000000000000000000000000000000000123\r\n" +
            "Content-Disposition: form-data; name=\"hello\"\r\n" +
            "\r\n" +
            "hello you\r\n" +
            "--boundary00000000000000000000000000000000000000123--\r\n").split("Binary image data goes here");

        var baos = new ByteArrayOutputStream();
        baos.writeBytes(input[0].getBytes(StandardCharsets.UTF_8));
        baos.writeBytes(Files.readAllBytes(UploadTest.guangzhouChina.toPath()));
        baos.writeBytes(input[1].getBytes(StandardCharsets.UTF_8));

        try (var inputStream = inputStream(type, baos.toByteArray())) {
            var parser = new MultipartFormParser(tempDir(), "boundary00000000000000000000000000000000000000123", inputStream, 100, StandardCharsets.UTF_8);
            var form = parser.parseFully();
            assertThat(form.getAll("hello"), contains("hello you"));
            assertThat(form.uploadedFiles().entrySet(), hasSize(1));
            var image = form.uploadedFile("image");
            assertThat(image, notNullValue());
            assertThat(image.size(), equalTo(372_987L));
            assertThat(image.filename(), equalTo("广州, china        .jpeg"));
            assertThat(image.extension(), equalTo("jpeg"));
            var tempFile = image.asPath();
            assertThat(image.asFile().isFile(), equalTo(true));
            assertThat(Files.size(tempFile), equalTo(372_987L));

            var b64 = Base64.getEncoder();
            assertThat(b64.encodeToString(image.asBytes()),
                equalTo(b64.encodeToString(Files.readAllBytes(UploadTest.guangzhouChina.toPath()))));

            ((MuUploadedFile2) image).deleteFile();
            assertThat(Files.exists(tempFile), equalTo(false));
        }
    }


    private MultipartForm multipartForm(String type, String message) throws IOException {
        return multipartForm(type, message, "boundary");
    }
    private MultipartForm multipartForm(String type, String message, String boundary) throws IOException {
        var inputStream = getInput(type, message);
        var form = new MultipartFormParser(tempDir(), boundary, inputStream, 8192, StandardCharsets.UTF_8).parseFully();
        return form;
    }

    private static Path tempDir() throws IOException {
        return Files.createTempDirectory("muservertests");
    }


    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void ifThereAreNoBoundariesThenItCrashesWithSmallMessage(String type) throws IOException {
        var inputStream = getInput(type, "nope");
        var parser = new MultipartFormParser(tempDir(),
            "---------------------------40328356438088973481959884063", inputStream, 8192, StandardCharsets.UTF_8);
        assertThrows(EOFException.class, parser::discardPreamble);
    }

    @ParameterizedTest
    @ValueSource(strings = {"full", "one-by-one"})
    public void ifThereAreNoBoundariesThenItCrashesWithLargeMessage(String type) throws IOException {
        var inputStream = getInput(type, "longerthanboundaryanyway".repeat(1000));
        var parser = new MultipartFormParser(tempDir(),
            "---------------------------40328356438088973481959884063", inputStream, 8192, StandardCharsets.UTF_8);
        assertThrows(EOFException.class, parser::discardPreamble);
    }




    private InputStream getInput(String type, String message) {
        return getInput(type, message, StandardCharsets.UTF_8);
    }
    private InputStream getInput(String type, String message, Charset charset) {
        var bytes = message.getBytes(charset);
        return inputStream(type, bytes);
    }

    private static InputStream inputStream(String type, byte[] bytes) {
        if (type.equals("full")) {
            return new ByteArrayInputStream(bytes);
        } else if (type.equals("one-by-one")) {
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                @Override
                public int read(byte @NonNull[] b, int off, int len) throws IOException {
                    if (len == 0) return 0;
                    var one = in.read();
                    if (one == -1) return -1;
                    byte asByte = (byte) one;
                    b[off] = asByte;
                    return 1;
                }
            };
        } else throw new IllegalArgumentException("Unknown boundary type: " + type);
    }
}