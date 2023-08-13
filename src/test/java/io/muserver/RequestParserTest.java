package io.muserver;


import org.junit.Assert;
import org.junit.Test;
import scaffolding.StringUtils;

import javax.ws.rs.core.MediaType;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertThrows;

public class RequestParserTest {

    private final MyRequestListener listener = new MyRequestListener();
    private final RequestParser parser = new RequestParser(RequestParser.Options.defaultOptions, listener);


    @Test
    public void noHeadersAndNoBodySupported() throws InvalidRequestException {
        parser.offer(wrap("G"));
        parser.offer(wrap("ET"));
        parser.offer(wrap(" /"));
        parser.offer(wrap("a%20link HTTP/1.1\r"));
        parser.offer(wrap("\n\r\n"));
        assertThat(listener.isComplete, is(true));
        assertThat(listener.method, is(Method.GET));
        assertThat(listener.uri.toString(), is("/a%20link"));
        assertThat(listener.proto, is(HttpVersion.HTTP_1_1));
        assertThat(listener.headers.size(), is(0));

        MyRequestListener anotherListener = new MyRequestListener();
        RequestParser another = new RequestParser(RequestParser.Options.defaultOptions, anotherListener);
        another.offer(wrap("GET /a%20link HTTP/1.1\r\n\r\n"));
        assertThat(anotherListener, equalTo(listener));
    }

    @Test
    public void http1_0NotSupported() throws InvalidRequestException {
        parser.offer(wrap("G"));
        parser.offer(wrap("ET"));
        parser.offer(wrap(" /"));
        parser.offer(wrap("a%20link HTTP/1.0\r"));
        assertThrows(InvalidRequestException.class, () -> parser.offer(wrap("\n\r\n")));
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
    public void urisAreNormalised() throws InvalidRequestException {
        parser.offer(wrap("GET /a/./b/../c//d HTTP/1.1\r\n\r\n"));
        assertThat(listener.uri.toString(), is("/a/c/d"));
    }

    @Test(expected = InvalidRequestException.class)
    public void urisCannotStartWithDot() throws InvalidRequestException {
        parser.offer(wrap("GET ./a HTTP/1.1\r\n\r\n"));
    }

    @Test(expected = InvalidRequestException.class)
    public void pathsGoingAboveRootAreNotAllowed() throws InvalidRequestException {
        parser.offer(wrap("GET /a/../../../b HTTP/1.1\r\n\r\n"));
    }

    @Test
    public void fixedLengthBodiesCanBeSent() throws Exception {
        String message = StringUtils.randomStringOfLength(20000) + "This & that I'm afraid is my message\r\n";
        byte[] messageBytes = message.getBytes(UTF_8);

        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: " + (messageBytes.length * 2) + "\r\n\r\n"));
        parser.offer(ByteBuffer.wrap(messageBytes));
        parser.offer(ByteBuffer.wrap(messageBytes));

        assertThat(listener.bodyAsString(), is(message + message));
    }

    @Test
    public void wholeRequestCanComeInAtOnce() throws Exception {
        String message = "Hello, there";
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message));
        assertThat(listener.bodyAsString(), is(message));
    }

    @Test
    public void zeroLengthBodiesAreFine() throws Exception {
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n"));
        parser.offer(ByteBuffer.allocate(0));
        assertThat(listener.hasBody, is(false));
        assertThat(listener.body, empty());
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
        assertThat(listener.bodyAsString(), is("Hello Hello Hello "));
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
        assertThat(listener.bodyAsString(), is("Hello Hello "));
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
        assertThat(listener.bodyAsString(), is("Hello Hello "));

        MyRequestListener listener2 = new MyRequestListener();
        RequestParser p2 = new RequestParser(RequestParser.Options.defaultOptions, listener2);
        byte[] inBytes = in.getBytes(UTF_8);
        for (int i = 0; i < inBytes.length; i++) {
            p2.offer(ByteBuffer.wrap(inBytes, i, 1));
        }
        assertThat(listener2, equalTo(listener));
        assertThat(listener2.trailers, equalTo(listener.trailers));
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
        for (ByteBuffer buf : listener.body) {
            byte[] dest = new byte[buf.remaining()];
            buf.get(dest);
            to.write(dest);
        }

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

        assertThat(listener.bodyAsString(), is(message + message));
        listener.reset();

        message = "Hello, there";
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message));
        assertThat(listener.bodyAsString(), is(message));

        listener.reset();
        parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n"));
        parser.offer(ByteBuffer.allocate(0));

        assertThat(listener.body, empty());
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
        boolean isComplete;
        boolean hasBody;
        List<ByteBuffer> body = new ArrayList<>();

        public void reset() {
            headers = null;
            proto = null;
            uri = null;
            method = null;
            trailers = null;
            isComplete = false;
            hasBody = false;
            body.clear();
        }

        @Override
        public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, boolean hasBody) {
            this.hasBody = hasBody;
            this.method = method;
            this.uri = uri;
            this.proto = httpProtocolVersion;
            this.headers = headers;
        }

        @Override
        public void onBody(ByteBuffer buffer) {
            var copy = ByteBuffer.allocate(buffer.remaining());
            copy.put(buffer);
            copy.flip();
            body.add(copy);
        }

        @Override
        public void onRequestComplete(MuHeaders trailers) {
            this.trailers = trailers;
            this.isComplete = true;
        }

        public ByteBuffer fullBody() {
            int totalSize = body.stream().map(Buffer::remaining).reduce(0, Integer::sum);
            var copy = ByteBuffer.allocate(totalSize);
            for (ByteBuffer byteBuffer : body) {
                byteBuffer.mark();
                copy.put(byteBuffer);
                byteBuffer.reset();
            }
            copy.flip();
            return copy;
        }

        public String bodyAsString() {
            ByteBuffer bb = fullBody();
            MediaType contentType = headers.contentType();
            Charset bodyCharset = contentType == null ? UTF_8 : Charset.forName(contentType.getParameters().getOrDefault("charset", "utf8"));
            var cb = bodyCharset.decode(bb);
            return cb.toString();
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


To allow for transition to the absolute-form for all requests in some
   future version of HTTP, a server MUST accept the absolute-form in
   requests, even though HTTP/1.1 clients will only send them in
   requests to proxies.

   Additional status codes related
   to capacity limits have been defined by extensions to HTTP [RFC6585].



   A common defense against response splitting is to filter requests for
   data that looks like encoded CR and LF (e.g., "%0D" and "%0A").
   However, that assumes the application server is only performing URI
   decoding, rather than more obscure data transformations like charset
   transcoding, XML entity translation, base64 decoding, sprintf
   reformatting, etc.  A more effective mitigation is to prevent
   anything other than the server's core protocol libraries from sending
   a CR or LF within the header section, which means restricting the
   output of header fields to APIs that filter for bad octets and not
   allowing application servers to write directly to the protocol
   stream.



   A client that receives a 417 (Expectation Failed) status code in
      response to a request containing a 100-continue expectation SHOULD
      repeat that request without a 100-continue expectation, since the
      417 response merely indicates that the response chain does not
      support expectations (e.g., it passes through an HTTP/1.0 server).



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

    /*
    A server that receives a request message with a transfer coding it does not understand SHOULD respond with 501 (Not Implemented).

     */

    /*
    A server or client that receives an HTTP/1.0 message containing a Transfer-Encoding header field MUST treat the message as if the framing is faulty, even if a Content-Length is present, and close the connection after processing the message.
     */

    /*
    If a Transfer-Encoding header field is present in a request and the chunked transfer coding is not the final encoding, the message body length cannot be determined reliably; the server MUST respond with the 400 (Bad Request) status code and then close the connection.
     */


}