package io.muserver;


import org.hamcrest.Matcher;
import org.junit.Test;
import scaffolding.StringUtils;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

public class RequestParserTest {

    private final RequestParser parser = new RequestParser(8192, 1024 * 24);


    @Test
    public void noHeadersAndNoBodySupported() throws InvalidRequestException {
        assertThat(parser.offer(wrap("G")), nullValue());
        assertThat(parser.offer(wrap("ET")), nullValue());
        assertThat(parser.offer(wrap(" /")), nullValue());
        assertThat(parser.offer(wrap("a%20link HTTP/1.1\r")), nullValue());
        assertThat(parser.offer(wrap("\n\r\n")), equalTo(
            new NewRequest(HttpVersion.HTTP_1_1, Method.GET, URI.create("/a%20link"), new MuHeaders(), false)
            ));

        assertThat(parser.offer(wrap("GET /a%20link HTTP/1.1\r\n\r\n")), equalTo(
            new NewRequest(HttpVersion.HTTP_1_1, Method.GET, URI.create("/a%20link"), new MuHeaders(), false)
        ));
    }

    @Test
    public void http1_0NotSupported() throws InvalidRequestException {
        assertThat(parser.offer(wrap("G")), nullValue());
        assertThat(parser.offer(wrap("ET")), nullValue());
        assertThat(parser.offer(wrap(" /")), nullValue());
        assertThat(parser.offer(wrap("a%20link HTTP/1.0\r")), nullValue());
        assertThrows(InvalidRequestException.class, () -> parser.offer(wrap("\n\r\n")));
    }

    @Test
    public void headersComeOutAsHeaders() throws InvalidRequestException {
        parser.offer(wrap("GET / HTTP/1.1\r\n"));
        var obj = parser.offer(wrap("Host:localhost:1234\r\nx-blah: haha\r\nSOME-Length: 0\r\nX-BLAH: \t something else\t \r\n\r\n"));
        assertThat(obj, instanceOf(NewRequest.class));
        NewRequest req = (NewRequest) obj;
        var headers = req.headers();
        assertThat(headers.getAll("Host"), contains("localhost:1234"));
        assertThat(headers.getAll("Host"), contains("localhost:1234"));
        assertThat(headers.getAll("some-length"), contains("0"));
        assertThat(headers.getAll("X-Blah"), contains("haha", "something else"));
        assertThat(req.hasBody(), is(false));
    }

    @Test
    public void urisAreNormalised() throws InvalidRequestException {
        var req = parser.offer(wrap("GET /a/./b/../c//d HTTP/1.1\r\n\r\n"));
        assertThat(((NewRequest)req).uri().toString(), is("/a/c/d"));
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

        var requestHeaderBytes = ("POST / HTTP/1.1\r\ncontent-length: " + (messageBytes.length * 2) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer headersAndFirstBody = ByteBuffer.allocate(requestHeaderBytes.length + messageBytes.length);
        headersAndFirstBody.put(requestHeaderBytes);
        headersAndFirstBody.put(messageBytes);
        headersAndFirstBody.flip();

        var req = (NewRequest)parser.offer(headersAndFirstBody);
        assertThat(req.hasBody(), equalTo(true));
        assertThat(headersAndFirstBody.remaining(), equalTo(messageBytes.length));


        var body1 = (RequestBodyData)parser.offer(headersAndFirstBody);
        assertThat(body1.last(), equalTo(false));
        assertThat(body1.buffer().remaining(), equalTo(messageBytes.length));

        assertThat(headersAndFirstBody.remaining(), is(0));
        assertThat(parser.offer(headersAndFirstBody), nullValue());

        var body2 = (RequestBodyData)parser.offer(ByteBuffer.wrap(messageBytes));
        assertThat(body2.last(), equalTo(true));
        assertThat(body2.buffer().remaining(), equalTo(messageBytes.length));

        assertThat(toString(body1.buffer(), body2.buffer()), equalTo(message + message));
    }

    private String toString(ByteBuffer... buffers) {
        int totalSize = Stream.of(buffers).mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer combinedByteBuffer = ByteBuffer.allocate(totalSize);
        for (ByteBuffer byteBuffer : buffers) {
            combinedByteBuffer.put(byteBuffer);
        }
        byte[] byteArray = combinedByteBuffer.array();
        return new String(byteArray, UTF_8);
    }

    @Test
    public void wholeRequestCanComeInAtOnce() throws Exception {
        String message = "Hello, there";
        ByteBuffer input = wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message);
        var newReq = (NewRequest) parser.offer(input);
        assertThat(newReq.hasBody(), is(true));
        var body = (RequestBodyData) parser.offer(input);
        assertThat(body.last(), is(true));
        assertThat(toString(body.buffer()), equalTo("Hello, there"));
    }

    @Test
    public void zeroLengthBodiesAreFine() throws Exception {
        var req = (NewRequest) parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n"));
        assertThat(req.hasBody(), is(false));
        assertThat(req.method(), is(Method.POST));
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
            "D;ignore;some=value;another=\"I'm \"good\" or something\"\r\n Hello again \r\n" +
            "17\r\nHello for the last time\r\n" +
            "0\r\n\r\n";
        ByteBuffer input = wrap(in);
        var newReq = (NewRequest)parser.offer(input);
        assertThat(newReq.hasBody(), equalTo(true));
        var chunk1 = (RequestBodyData) parser.offer(input);
        assertThat(chunk1.last(), is(false));
        assertThat(toString(chunk1.buffer()), equalTo("Hello "));

        var chunk2 = (RequestBodyData) parser.offer(input);
        assertThat(chunk2.last(), is(false));
        assertThat(toString(chunk2.buffer()), equalTo(" Hello again "));

        var chunk3 = (RequestBodyData) parser.offer(input);
        assertThat(chunk3.last(), is(false));
        assertThat(toString(chunk3.buffer()), equalTo("Hello for the last time"));

        var eos = (EndOfChunks) parser.offer(input);
        assertThat(eos.trailers(), sameInstance(MuHeaders.EMPTY));
    }

    @Test
    public void trailersCanBeSpecified() throws Exception {
        var in = wrap("POST / HTTP/1.1\r\n" +
            "transfer-encoding: chunked\r\n" +
            "x-header: blah\r\n" +
            "\r\n" +
            "6\r\nHello \r\n" +
            "6\r\nHello \r\n" +
            "0\r\n" +
            "x-trailer-one: blart\r\n" +
            "X-Trailer-TWO: blart2\r\n" +
            "x-trailer-two: and another value\r\n" +
            "\r\n");
        assertThat(parser.offer(in), instanceOf(NewRequest.class));
        assertThat(parser.offer(in), instanceOf(RequestBodyData.class));
        assertThat(parser.offer(in), instanceOf(RequestBodyData.class));
        ConMessage last = parser.offer(in);
        assertThat(last, instanceOf(EndOfChunks.class));
        var eoc = (EndOfChunks) last;
        assertThat(eoc.trailers().getAll("X-Trailer-One"), contains("blart"));
        assertThat(eoc.trailers().getAll("X-Trailer-Two"), contains("blart2", "and another value"));
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
        ByteBuffer req1Input = wrap(in);
        assertThat(parser.offer(req1Input), instanceOf(NewRequest.class));
        assertThat(toString(
            ((RequestBodyData)parser.offer(req1Input)).buffer(),
            ((RequestBodyData)parser.offer(req1Input)).buffer()
        ), is("Hello Hello "));
        assertThat(parser.offer(req1Input), instanceOf(EndOfChunks.class));

        byte[] inBytes = in.getBytes(UTF_8);
        var receivedBody = new StringBuilder();
        Class<? extends ConMessage> expectedMsgType = null;
        for (int i = 0; i < inBytes.length; i++) {
            var msg = parser.offer(ByteBuffer.wrap(inBytes, i, 1));
            if (msg instanceof RequestBodyData) {
                receivedBody.append(toString(((RequestBodyData) msg).buffer()));
            }
            if (i == 46) expectedMsgType = NewRequest.class;
            if (i == 47) expectedMsgType = null;
            if (i == 50) expectedMsgType = RequestBodyData.class;
            if (i == 56) expectedMsgType = null;
            if (i == 61) expectedMsgType = RequestBodyData.class;
            if (i == 67) expectedMsgType = null;
            if (i == 152) expectedMsgType = EndOfChunks.class;

            Matcher<ConMessage> matcher = expectedMsgType == null ? nullValue(ConMessage.class) : instanceOf(expectedMsgType);
            assertThat("i=" + i, msg, matcher);
        }
        assertThat(receivedBody.toString(), equalTo("Hello Hello "));
    }

    @Test
    public void requestBodiesCanBeChunked() throws Exception {
        var newReq = (NewRequest) parser.offer(wrap("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n"));
        assertThat(newReq.hasBody(), equalTo(true));
        for (int i = 1; i < 10; i++) {
            byte[] chunk = StringUtils.randomBytes(777 * i);
            assertThat(parser.offer(wrap(Integer.toHexString(chunk.length) + "\r\n")), nullValue());
            var body = (RequestBodyData) parser.offer(ByteBuffer.wrap(chunk));
            assertThat(body.last(), equalTo(false));
            assertThat(body.buffer().array(), equalTo(chunk));
            assertThat(parser.offer(wrap("\r\n")), nullValue());
        }
        assertThat(parser.offer(wrap("0\r\n")), nullValue());
        var eod = (EndOfChunks)parser.offer(wrap("\r\n"));
        assertThat(eod.trailers(), sameInstance(MuHeaders.EMPTY));
    }

    @Test
    public void multipleRequestsCanBeHandled() throws Exception {
        String message = StringUtils.randomStringOfLength(200) + "This & that I'm afraid is my message\r\n";
        byte[] messageBytes = message.getBytes(UTF_8);

        assertThat(parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: " + (messageBytes.length * 2) + "\r\n\r\n")), instanceOf(NewRequest.class));
        assertThat(parser.offer(ByteBuffer.wrap(messageBytes)), instanceOf(RequestBodyData.class));
        assertThat(parser.offer(ByteBuffer.wrap(messageBytes)), instanceOf(RequestBodyData.class));

        message = "Hello, there";
        ByteBuffer req2 = wrap("POST / HTTP/1.1\r\ncontent-length:" + (message.getBytes(UTF_8).length) + "\r\n\r\n" + message);
        assertThat(parser.offer(req2), instanceOf(NewRequest.class));
        assertThat(parser.offer(req2), instanceOf(RequestBodyData.class));

        assertThat(parser.offer(wrap("POST / HTTP/1.1\r\ncontent-length: 0\r\n\r\n")), instanceOf(NewRequest.class));
    }


    private static ByteBuffer wrap(String in) {
        return ByteBuffer.wrap(in.getBytes(StandardCharsets.US_ASCII));
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