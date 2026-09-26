package io.muserver;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http1MessageParserTest {

    @Test
    void oversizedChunkSizeIsAParseError() throws Exception {
        var raw = ("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
            + "1555555555555555555\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        var parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(raw), 8192, 8192);
        parser.readNext();
        assertThrows(ParseException.class, parser::readNext);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0\r X\r\n\r\nGET /smuggled HTTP/1.1\r\nHost: localhost\r\n\r\n",
        "1 \r\nx\r\n0\r\n\r\n",
        "1\t\r\nx\r\n0\r\n\r\n",
        "1;\r\nx\r\n0\r\n\r\n",
        "1;name;\r\nx\r\n0\r\n\r\n",
        "1;name=value extra\r\nx\r\n0\r\n\r\n",
        "1;name=\"ok\"extra\r\nx\r\n0\r\n\r\n",
        "1;bad=\r\nx\r\n0\r\n\r\n",
        "1;bad=\"unterminated\r\nx\r\n0\r\n\r\n",
        "1;bad=\"has\\\u0001control\"\r\nx\r\n0\r\n\r\n",
        "1;bad=\"has\u007fdel\"\r\nx\r\n0\r\n\r\n",
        "1;bad\nextension\r\nx\r\n0\r\n\r\n"
    })
    void invalidChunkExtensionLinesAreParseErrors(String chunks) throws Exception {
        var raw = ("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + chunks)
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(raw), 8192, 8192);

        parser.readNext();
        assertThrows(ParseException.class, parser::readNext);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "content-length: nope",
        "content-length: +1",
        "content-length: -0",
        "content-length: -1",
        "content-length: 1x",
        "transfer-encoding: chunked, identity",
        "transfer-encoding: chunked, chunked",
        "transfer-encoding: chunked,",
        "transfer-encoding: chunked;param=value",
        "transfer-encoding: gzip",
        "transfer-encoding: chunked\r\ntransfer-encoding: gzip",
        "transfer-encoding: gzip\r\ncontent-length: 1",
        "content-length: 1\r\ncontent-length: 2",
        "transfer-encoding: chunked\r\ncontent-length: 1"
    })
    void invalidRequestFramingIsRejectedWithConnectionClose(String headers) throws Exception {
        var raw = ("POST / HTTP/1.1\r\n" + headers + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        var parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(raw), 8192, 8192);

        var request = (HttpRequestTemp) parser.readNext();
        var rejection = request.getRejectRequest();
        assertThat(rejection, notNullValue());
        assertThat(rejection.status().code(), equalTo(400));
        assertThat(rejection.responseHeaders().get("connection"), equalTo("close"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chunked", "CHUNKED", "gzip, chunked", "gzip\r\nTransfer-Encoding: chunked"})
    void finalChunkedCodingIsRecognisedAcrossFieldLines(String value) throws Exception {
        String wire = "POST / HTTP/1.1\r\nTransfer-Encoding: " + value + "\r\n\r\n0\r\n\r\n";
        var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>(),
            new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), 8192, 8192);
        var request = (HttpRequestTemp) parser.readNext();
        assertThat(request.getRejectRequest(), nullValue());
        assertThat(request.getBodySize(), equalTo(BodySize.CHUNKED));
        assertThat(((MessageBodyBit) parser.readNext()).isLast(), equalTo(true));
    }


    @Test
    void http10RequestWithTransferEncodingAndContentLengthIsRejectedWithConnectionClose() throws Exception {
        var raw = ("POST / HTTP/1.0\r\n"
            + "transfer-encoding: chunked\r\n"
            + "content-length: 1\r\n"
            + "\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        var parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(raw), 8192, 8192);

        var request = (HttpRequestTemp) parser.readNext();
        var rejection = request.getRejectRequest();
        assertThat(rejection, notNullValue());
        assertThat(rejection.status().code(), equalTo(400));
        assertThat(rejection.responseHeaders().get("connection"), equalTo("close"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET / HTTP/1.1\r\nBad Header: x\r\n\r\n", "GET / HTTP/1.1\r\nName: has\nlf\r\n\r\n"})
    void invalidHeaderOctetsAreParseErrors(String request) {
        var parser = new Http1MessageParser(HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(), new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)), 8192, 8192);

        assertThrows(ParseException.class, parser::readNext);
    }

    @Test
    void chunkedOutputStreamSingleByteWriteUsesAsciiChunkSize() throws Exception {
        var raw = new ByteArrayOutputStream();
        var out = new ChunkedOutputStream(raw);

        out.write('x');

        assertThat(raw.toByteArray(), equalTo(new byte[] {'1', '\r', '\n', 'x', '\r', '\n'}));
    }

    @Test
    void emptyHeaderValuesAreRetained() throws IOException, ParseException {
        String requestString = "GET /blah HTTP/1.1\r\n" +
            "accept-encoding:\r\n" +
            "accept-encoding-2: \r\n" +
            "accept-encoding-3:  \r\n" +
            "content-length: 0\r\n" +
            "\r\n";

        var bais = new ByteArrayInputStream(requestString.getBytes(StandardCharsets.UTF_8));
        var parser = new Http1MessageParser(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>(), bais, 8192, 8192);
        var req = (HttpRequestTemp)parser.readNext();
        assertThat(req.headers().toString(), req.headers().size(), equalTo(4));
        assertThat(req.headers().getAll("accept-encoding"), Matchers.contains(""));
        assertThat(req.headers().getAll("accept-encoding-2"), Matchers.contains(""));
        assertThat(req.headers().getAll("accept-encoding-3"), Matchers.contains(""));
        assertThat(req.headers().getAll("content-length"), Matchers.contains("0"));
    }

    @Test
    void chunkedBodiesWhereWholeBodyInSingleBufferIsFine() throws IOException, ParseException {
        StringBuilder requestString = new StringBuilder()
            .append("GET /blah HTTP/1.1\r\n")
            .append("content-type: text/plain;charset=utf-8\r\n")
            .append("transfer-encoding: chunked\r\n")
            .append("some-header1: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header2: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header3: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header4: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header5: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header6: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("some-header7: some-value some-value some-value some-value some-value some-value some-value some-value\r\n")
            .append("\r\n");

        String chunk = "!".repeat(7429);
        String chunkSizeHex = Integer.toHexString(chunk.getBytes().length).toUpperCase();
        requestString.append(chunkSizeHex).append("\r\n").append(chunk).append("\r\n0\r\n\r\n");

        ByteArrayInputStream bais = new ByteArrayInputStream(requestString.toString().getBytes(StandardCharsets.UTF_8));
        Http1MessageParser parser = new Http1MessageParser(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>(), bais, 8192, 8192);

        HttpRequestTemp request = (HttpRequestTemp) parser.readNext();
        assertThat(request.getMethod(), equalTo(Method.GET));
        assertThat(request.getUrl(), equalTo("/blah"));
        assertThat(request.getHttpVersion(), equalTo(HttpVersion.HTTP_1_1));

        MessageBodyBit body = (MessageBodyBit) parser.readNext();
        StringBuilder bodyContent = new StringBuilder(new String(body.bytes(), body.offset(), body.length()));
        assertThat(body.isLast(), equalTo(false));

        MessageBodyBit body2 = (MessageBodyBit) parser.readNext();
        bodyContent.append(new String(body2.bytes(), body2.offset(), body2.length()));
        assertThat(bodyContent.toString(), equalTo(chunk));
        assertThat(body2.isLast(), equalTo(false));
        assertThat(parser.readNext(), sameInstance(MessageBodyBit.EndOfBodyBit));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 11, 20, 1000})
    void chunkedBodiesCanSpanMultipleChunks(int maxBytesPerRead) throws IOException, ParseException {
        StringBuilder request = new StringBuilder()
            .append("GET /blah HTTP/1.1\r\n")
            .append("content-type: text/plain;charset=utf-8\r\n")
            .append("transfer-encoding: chunked\r\n")
            .append("\r\n");

        String chunk = "Hello world there oh yes";
        String chunkSizeHex = Integer.toHexString(chunk.getBytes().length).toUpperCase();
        request.append(chunkSizeHex)
            .append(";chunkmetadata=blah;another=value\r\n")
            .append(chunk)
            .append("\r\n0\r\ntrailer: hello\r\n\r\n");

        byte[] wholeMessage = request.toString().getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = new MaxReadLengthInputStream(new ByteArrayInputStream(wholeMessage), maxBytesPerRead);
        Http1MessageParser parser = new Http1MessageParser(HttpMessageType.REQUEST, new ConcurrentLinkedQueue<>(), inputStream, 8192, 8192);

        HttpRequestTemp req = (HttpRequestTemp) parser.readNext();
        assertThat(req.getMethod(), equalTo(Method.GET));
        assertThat(req.getUrl(), equalTo("/blah"));
        assertThat(req.getHttpVersion(), equalTo(HttpVersion.HTTP_1_1));

        Http1ConnectionMsg bit;
        var contentReceived = new ByteArrayOutputStream();
        while ((bit = parser.readNext()) instanceof MessageBodyBit &&
            bit != MessageBodyBit.EndOfBodyBit) {
            MessageBodyBit body = (MessageBodyBit) bit;
            contentReceived.write(body.bytes(), body.offset(), body.length());
        }
        assertThat(contentReceived.toString(StandardCharsets.UTF_8), equalTo(chunk));
        assertThat(bit, sameInstance(MessageBodyBit.EndOfBodyBit));
    }

    @Test
    void http10RequestsDoNotRequireHostHeader() throws IOException, ParseException {
        String requestString = "GET /legacy HTTP/1.0\r\n" +
            "content-length: 0\r\n" +
            "\r\n";
        var parser = new Http1MessageParser(
            HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(),
            new ByteArrayInputStream(requestString.getBytes(StandardCharsets.UTF_8)),
            8192,
            8192
        );

        var req = (HttpRequestTemp) parser.readNext();
        assertThat(req.getHttpVersion(), equalTo(HttpVersion.HTTP_1_0));
        assertThat(req.headers().contains(HeaderNames.HOST), equalTo(false));
        assertThat(req.getRejectRequest(), nullValue());
    }

    @Test
    void maxHeadersSizeAppliesToTheWholeHeaderSection() throws IOException, ParseException {
        String requestString = "GET /blah HTTP/1.1\r\n" +
            "a: 12345\r\n" +
            "b: 12345\r\n" +
            "\r\n";

        var parser = new Http1MessageParser(
            HttpMessageType.REQUEST,
            new ConcurrentLinkedQueue<>(),
            new ByteArrayInputStream(requestString.getBytes(StandardCharsets.UTF_8)),
            12,
            8192
        );

        var req = (HttpRequestTemp) parser.readNext();
        assertThat(req.getRejectRequest(), notNullValue());
        assertThat(req.getRejectRequest().status(), equalTo(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE_431));
    }

    private static class MaxReadLengthInputStream extends FilterInputStream {
        private final byte[] temp;

        public MaxReadLengthInputStream(InputStream underlying, int maxBytesPerRead) {
            super(underlying);
            this.temp = new byte[maxBytesPerRead];
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len > temp.length) {
                int actual = super.read(temp, 0, temp.length);
                System.arraycopy(temp, 0, b, off, actual);
                return actual;
            } else {
                return super.read(b, off, len);
            }
        }

    }

}
