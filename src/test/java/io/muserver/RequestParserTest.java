package io.muserver;


import org.junit.Assert;
import org.junit.Test;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class RequestParserTest {

    private final MyRequestListener listener = new MyRequestListener();
    private final RequestParser parser = new RequestParser(listener);


    @Test
    public void noHeadersAndNoBodySupported() throws InvalidRequestException {
        parser.offer(wrap("G"));
        parser.offer(wrap("ET"));
        parser.offer(wrap(" /"));
        parser.offer(wrap("a%20link HTTP/1.0\r"));
        parser.offer(wrap("\n\r\n"));
        assertThat(listener.isComplete, is(true));
        assertThat(listener.method, is(Method.GET));
        assertThat(listener.uri.toString(), is("/a%20link"));
        assertThat(listener.proto, is("HTTP/1.0"));
        assertThat(listener.headers.size(), is(0));

        MyRequestListener anotherListener = new MyRequestListener();
        RequestParser another = new RequestParser(anotherListener);
        another.offer(wrap("GET /a%20link HTTP/1.0\r\n\r\n"));
        assertThat(anotherListener, equalTo(listener));
    }

    @Test
    public void headersComeOutAsHeaders() throws InvalidRequestException {
        parser.offer(wrap("GET / HTTP/1.1\r\n"));
        parser.offer(wrap("Host:localhost:1234\r\nx-blah: haha\r\nSOME-Length: 0\r\nX-BLAH: \t something else\t \r\n\r\n"));
        assertThat(listener.headers.getAll("Host"), contains("localhost:1234"));
        assertThat(listener.headers.getAll("Host"), contains("localhost:1234"));
        assertThat(listener.headers.getAll("some-length"), contains("0"));
        assertThat(listener.headers.getAll("X-Blah"), contains("haha", "something else"));
        assertThat(listener.isComplete, is(true));
    }

    @Test
    public void fixedLengthBodiesCanBeSent() throws Exception {
        String message = StringUtils.randomStringOfLength(20000) + "This & that I'm afraid is my message\r\n";
        byte[] messageBytes = message.getBytes(UTF_8);

        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: " + (messageBytes.length * 2) + "\r\n\r\n"));
        parser.offer(ByteBuffer.wrap(messageBytes));
        parser.offer(ByteBuffer.wrap(messageBytes));

        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.toString("UTF-8"), is(message + message));
    }

    @Test
    public void wholeRequestCanComeInAtOnce() throws Exception {
        String message = "Hello, there";
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message));
        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.toString("UTF-8"), is(message));
    }

    @Test
    public void zeroLengthBodiesAreFine() throws Exception {
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n"));
        parser.offer(ByteBuffer.allocate(0));
        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.size(), is(0));
    }

    @Test
    public void hexWorksLikeIThinkItDoes() {
        assertThat(Integer.toHexString(15), equalTo("f"));
        assertThat(Integer.toHexString(16), equalTo("10"));
        assertThat(Integer.toHexString(13434546), equalTo("ccfeb2"));
        assertThat(Integer.parseInt("f", 16), is(15));
        assertThat(Integer.parseInt("F", 16), is(15));
        assertThat(Integer.parseInt("10", 16), is(16));
        assertThat(Integer.parseInt("ccfeb2", 16), is(13434546));
    }


    @Test
    public void chunkExtensionsAreIgnored() throws Exception {
        String in = "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n" +
            "6;ignore\r\nHello \r\n" +
            "6;ignore;some=value;another=\"I'm \"good\" or something\"\r\nHello \r\n" +
            "6\r\nHello \r\n" +
            "0\r\n\r\n";
        parser.offer(wrap(in));
        assertThat(listener.isComplete, is(true));
        assertThat(bodyAsUTF8(listener), is("Hello Hello Hello "));
    }

    @Test
    public void trailersCanBeSpecified() throws Exception {
        String in = "POST / HTTP/1.1\r\n" +
            "transfer-encoding: chunked\r\n" +
            "x-header: blah\r\n" +
            "\r\n" +
            "6\r\nHello \r\n" +
            "6\r\nHello \r\n" +
            "0\r\n" +
            "x-trailer-one: blart\r\n" +
            "X-Trailer-TWO: blart2\r\n" +
            "x-trailer-two: and another value\r\n" +
            "\r\n";
        parser.offer(wrap(in));
        assertThat(listener.isComplete, is(true));
        assertThat(bodyAsUTF8(listener), is("Hello Hello "));
        assertThat(listener.trailers.getAll("X-Trailer-One"), contains("blart"));
        assertThat(listener.trailers.getAll("X-Trailer-Two"), contains("blart2", "and another value"));
    }

    @Test
    public void chunkingCanAllHappenInOneOfferOrByteByByte() throws Exception {
        String in = "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n" +
            "6\r\nHello \r\n" +
            "6\r\nHello \r\n" +
            "0\r\n" +
            "x-trailer-one: blart\r\n" +
            "X-Trailer-TWO: blart2\r\n" +
            "x-trailer-two: and another value\r\n" +
            "\r\n";
        parser.offer(wrap(in));
        assertThat(listener.isComplete, is(true));
        assertThat(bodyAsUTF8(listener), is("Hello Hello "));

        MyRequestListener listener2 = new MyRequestListener();
        RequestParser p2 = new RequestParser(listener2);
        byte[] inBytes = in.getBytes(UTF_8);
        for (int i = 0; i < inBytes.length; i++) {
            p2.offer(ByteBuffer.wrap(inBytes, i, 1));
        }
        assertThat(listener2, equalTo(listener));
        assertThat(listener2.trailers, equalTo(listener.trailers));
    }

    private static String bodyAsUTF8(MyRequestListener listener) throws IOException {
        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        return to.toString("UTF-8");
    }

    @Test
    public void requestBodiesCanBeChunked() throws Exception {
        List<Byte> allSent = new ArrayList<>();
        parser.offer(wrap("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n"));
        for (int i = 1; i < 10; i++) {
            byte[] chunk = StringUtils.randomBytes(777 * i);
            for (byte b : chunk) {
                allSent.add(b);
            }
            parser.offer(wrap(Integer.toHexString(chunk.length) + "\r\n"));
            parser.offer(ByteBuffer.wrap(chunk));
            parser.offer(wrap("\r\n"));
        }
        parser.offer(wrap("0\r\n"));

        // todo: trailer
        parser.offer(wrap("\r\n"));
        assertThat(listener.isComplete, is(true));
        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);

        byte[] actual = to.toByteArray();
        assertThat(actual.length, is(allSent.size()));
        int i = 0;
        for (byte aByte : allSent) {
            byte a = actual[i];
            if (a != aByte) {
                Assert.fail("Error at index " + i);
            }
            i++;
        }
    }

    @Test
    public void multipleRequestsCanBeHandled() throws Exception {
        String message = StringUtils.randomStringOfLength(200) + "This & that I'm afraid is my message\r\n";
        byte[] messageBytes = message.getBytes(UTF_8);

        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: " + (messageBytes.length * 2) + "\r\n\r\n"));
        parser.offer(ByteBuffer.wrap(messageBytes));
        parser.offer(ByteBuffer.wrap(messageBytes));

        ByteArrayOutputStream to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.toString("UTF-8"), is(message + message));


        message = "Hello, there";
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message));
        to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.toString("UTF-8"), is(message));


        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n"));
        parser.offer(ByteBuffer.allocate(0));

        to = new ByteArrayOutputStream();
        Mutils.copy(listener.body, to, 8192);
        assertThat(to.size(), is(0));
    }


    private static ByteBuffer wrap(String in) {
        return ByteBuffer.wrap(in.getBytes(StandardCharsets.US_ASCII));
    }

    private static class MyRequestListener implements RequestParser.RequestListener {
        MuHeaders headers;
        HttpVersion proto;
        URI uri;
        Method method;
        MuHeaders trailers;
        InputStream body;
        boolean isComplete;

        @Override
        public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, InputStream body) {
            this.body = body;
            this.method = method;
            this.uri = uri;
            this.proto = httpProtocolVersion;
            this.headers = headers;
        }

        @Override
        public void onRequestComplete(MuHeaders trailers) {
            this.trailers = trailers;
            this.isComplete = true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MyRequestListener that = (MyRequestListener) o;
            return Objects.equals(headers, that.headers) &&
                Objects.equals(proto, that.proto) &&
                Objects.equals(uri, that.uri) &&
                Objects.equals(trailers, that.trailers) &&
                method == that.method;
        }

        @Override
        public int hashCode() {
            return Objects.hash(headers, proto, uri, trailers, method);
        }

        @Override
        public String toString() {
            return "MyRequestListener{" +
                "headers=" + headers +
                ", proto='" + proto + '\'' +
                ", uri=" + uri +
                ", method=" + trailers +
                ", method=" + method +
                '}';
        }
    }


    /*
    A server can send a 505
   (HTTP Version Not Supported) response if it wishes, for any reason,
   to refuse service of the client's major protocol version.

   fragments in request uri not allowed

   A sender MUST NOT send whitespace between the start-line and the
   first header field.  A recipient that receives whitespace between the
   start-line and the first header field MUST either reject the message
   as invalid or consume each whitespace-preceded line without further
   processing of it (i.e., ignore the entire line, along with any
   subsequent lines preceded by whitespace, until a properly formed
   header field is received or the header section is terminated).


   A
   server MUST reject any received request message that contains
   whitespace between a header field-name and colon with a response code
   of 400 (Bad Request)


   A server that receives an obs-fold in a request message that is not
   within a message/http container MUST either reject the message by
   sending a 400 (Bad Request), preferably with a representation
   explaining that obsolete line folding is unacceptable, or replace
   each received obs-fold with one or more SP octets prior to
   interpreting the field value or forwarding the message downstream.


Comments can be included in some HTTP header fields by surrounding
   the comment text with parentheses.  Comments are only allowed in
   fields containing "comment" as part of their field value definition



   Responses to the HEAD request method (Section 4.3.2
   of [RFC7231]) never include a message body because the associated
   response header fields (e.g., Transfer-Encoding, Content-Length,
   etc.), if present, indicate only what their values would have been if
   the request method had been GET (Section 4.3.1 of [RFC7231]). 2xx
   (Successful) responses to a CONNECT request method (Section 4.3.6 of
   [RFC7231]) switch to tunnel mode instead of having a message body.
   All 1xx (Informational), 204 (No Content), and 304 (Not Modified)
   responses do not include a message body.  All other responses do
   include a message body, although the body might be of zero length.


   A server MUST NOT send a Transfer-Encoding header field in any
   response with a status code of 1xx (Informational) or 204 (No
   Content).  A server MUST NOT send a Transfer-Encoding header field in
   any 2xx (Successful) response to a CONNECT request (Section 4.3.6 of
   [RFC7231]).

   A server MUST NOT send a
   response containing Transfer-Encoding unless the corresponding
   request indicates HTTP/1.1 (or later).

   A server that receives a request message with a transfer coding it
   does not understand SHOULD respond with 501 (Not Implemented).

   A sender MUST NOT send a Content-Length header field in any message
   that contains a Transfer-Encoding header field.

   If a message is received that has multiple Content-Length header
   fields with field-values consisting of the same decimal value, or a
   single Content-Length header field with a field value containing a
   list of identical decimal values (e.g., "Content-Length: 42, 42"),
   indicating that duplicate Content-Length header fields have been
   generated or combined by an upstream message processor, then the
   recipient MUST either reject the message as invalid or replace the
   duplicated field-values with a single valid Content-Length field
   containing that decimal value prior to determining the message body
   length or forwarding the message.

   If a Transfer-Encoding header field
       is present in a request and the chunked transfer coding is not
       the final encoding, the message body length cannot be determined
       reliably; the server MUST respond with the 400 (Bad Request)
       status code and then close the connection.

       If a message is received with both a Transfer-Encoding and a
       Content-Length header field, the Transfer-Encoding overrides the
       Content-Length.  Such a message might indicate an attempt to
       perform request smuggling

       If a message is received without Transfer-Encoding and with
       either multiple Content-Length header fields having differing
       field-values or a single Content-Length header field having an
       invalid value, then the message framing is invalid and the
       recipient MUST treat it as an unrecoverable error.  If this is a
       request message, the server MUST respond with a 400 (Bad Request)
       status code and then close the connection.

       A server MAY reject a request that contains a message body but not a
   Content-Length by responding with 411 (Length Required).

   A server that receives an incomplete request message, usually due to
   a canceled request or a triggered timeout exception, MAY send an
   error response prior to closing the connection

   In the interest of robustness, a server that is expecting to receive
   and parse a request-line SHOULD ignore at least one empty line (CRLF)
   received prior to the request-line.

   Although the line terminator for the start-line and header fields is
   the sequence CRLF, a recipient MAY recognize a single LF as a line
   terminator and ignore any preceding CR.


   When a server listening only for HTTP request messages, or processing
   what appears from the start-line to be an HTTP request message,
   receives a sequence of octets that does not match the HTTP-message
   grammar aside from the robustness exceptions listed above, the server
   SHOULD respond with a 400 (Bad Request) response.


https://noxxi.de/research/http-evader-explained-3-chunked.html


A server MUST respond with a 400 (Bad Request) status code to any
   HTTP/1.1 request message that lacks a Host header field and to any
   request message that contains more than one Host header field or a
   Host header field with an invalid field-value.

     */

}