package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** RFC 9110 field grammar, and the selected RFC 9112 request/response folding policies. */
class Http1FieldConformanceTest {
    private enum Section { INITIAL, TRAILER }

    static Stream<Arguments> contexts() {
        return Stream.of(HttpMessageType.REQUEST, HttpMessageType.RESPONSE)
            .flatMap(mode -> Stream.of(Section.values()).map(section -> Arguments.of(mode, section)));
    }

    static Stream<Arguments> tokenNames() {
        return contexts().flatMap(context -> TOKEN.chars().boxed().flatMap(c -> Stream.of(
            Arguments.of(context.get()[0], context.get()[1], Character.toString(c)),
            Arguments.of(context.get()[0], context.get()[1], "X-" + Character.toString(c) + "-End"))));
    }

    @Test
    void everyTokenCharacterIsAccepted() {
        for (Arguments args : tokenNames().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            HttpMessageType mode = (HttpMessageType) values[0];
            Section section = (Section) values[1];
            String name = (String) values[2];
            assertAll(mode + "/" + section + ": token " + name,
                () -> assertTokenNameAccepted(mode, section, name));
        }
    }

    private static void assertTokenNameAccepted(HttpMessageType mode, Section section, String name) throws Exception {
        FieldBlock fields = fields(mode, section, octets(name + ": MiXeD\r\n"), 7);
        assertEquals(List.of("MiXeD"), fields.getAll(name.toLowerCase(Locale.ROOT)));
    }

    static Stream<Arguments> invalidNames() {
        return contexts().flatMap(context -> IntStream.rangeClosed(0, 255)
            // A colon ends a wire name: it is tested as a delimiter, not as an invalid name byte.
            .filter(c -> c != ':' && TOKEN.indexOf(c) == -1).boxed()
            .flatMap(c -> IntStream.range(0, 3).mapToObj(position ->
                Arguments.of(context.get()[0], context.get()[1], c, position))));
    }

    @Test
    void nonTokenNameOctetsAreRejected() {
        for (Arguments args : invalidNames().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            HttpMessageType mode = (HttpMessageType) values[0];
            Section section = (Section) values[1];
            int octet = (int) values[2];
            int position = (int) values[3];
            assertAll(mode + "/" + section + ": name octet " + octet + " at " + position,
                () -> assertNonTokenNameRejected(mode, section, octet, position));
        }
    }

    private static void assertNonTokenNameRejected(HttpMessageType mode, Section section, int octet,
                                                    int position) throws Exception {
        String bad = Character.toString((char) octet);
        String name = position == 0 ? bad + "Name" : position == 1 ? "Na" + bad + "me" : "Name" + bad;
        assertInvalid(mode, section, octets(name + ": value\r\n"), 7);
    }

    static Stream<Arguments> invalidValues() {
        return contexts().flatMap(context -> IntStream.rangeClosed(0, 127)
            .filter(c -> (c < 32 && c != '\t') || c == 127).boxed()
            .flatMap(c -> IntStream.range(0, 3).mapToObj(position ->
                Arguments.of(context.get()[0], context.get()[1], c, position))));
    }

    @Test
    void controlsCannotBeHiddenByOuterWhitespace() {
        for (Arguments args : invalidValues().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            HttpMessageType mode = (HttpMessageType) values[0];
            Section section = (Section) values[1];
            int octet = (int) values[2];
            int position = (int) values[3];
            assertAll(mode + "/" + section + ": value control " + octet + " at " + position,
                () -> assertControlRejected(mode, section, octet, position));
        }
    }

    private static void assertControlRejected(HttpMessageType mode, Section section, int octet,
                                              int position) throws Exception {
        String bad = Character.toString((char) octet);
        String value = position == 0 ? " \t" + bad + "value" :
            position == 1 ? "val" + bad + "ue" : "value" + bad + "\t ";
        // Strict policy rejects controls beyond the mandatory CR/LF/NUL prohibition.
        assertInvalid(mode, section, octets("X-Control:" + value + "\r\n"), 7);
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void mixedCaseKnownAndCustomNamesDoNotChangeValueCase(HttpMessageType mode, Section section) throws Exception {
        FieldBlock fields = fields(mode, section, octets(
            "EtAg: MiXeD\r\nETAG: SECOND\r\nX-CuStOm: VaLuE\r\n"), 3);
        assertEquals(List.of("MiXeD", "SECOND"), fields.getAll("etag"));
        assertEquals(List.of("VaLuE"), fields.getAll("x-custom"));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void visibleValuesAndOnlyOuterAsciiWhitespaceArePreserved(HttpMessageType mode, Section section) throws Exception {
        String visible = IntStream.rangeClosed(33, 126)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        String value = visible + " two  spaces\tand\ttabs MiXeD";
        FieldBlock fields = fields(mode, section, octets(
            "X-No-OWS:" + value + "\r\nX-SP: " + value + " \r\nX-Tab:\t" + value + "\t\r\n"
                + "X-Mixed: \t " + value + " \t \r\n"), 7);
        for (String name : List.of("x-no-ows", "x-sp", "x-tab", "x-mixed")) {
            assertEquals(List.of(value), fields.getAll(name), name);
        }
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void emptyFieldsAndDuplicateValuesRemainPresentAndOrdered(HttpMessageType mode, Section section) throws Exception {
        FieldBlock fields = fields(mode, section, octets(
            "X-Empty:\r\nX-Empty: \t \r\nX-Empty: MiXeD\r\nX-Empty:\t\r\n"), 1);
        assertEquals(List.of("", "", "MiXeD", ""), fields.getAll("x-empty"));
        assertTrue(fields.contains("x-empty"));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void allOpaqueValueOctetsSurviveWithoutAsciiOrUtf8Conversion(HttpMessageType mode, Section section) throws Exception {
        byte[] opaque = new byte[128];
        for (int i = 0; i < opaque.length; i++) opaque[i] = (byte) (128 + i);
        byte[] lines = concat(octets("X-Opaque: \t"), opaque, octets("\t \r\nX-Edges: "),
            new byte[]{(byte) 0x85, (byte) 0xa0}, octets(" MiXeD "),
            new byte[]{(byte) 0xa0, (byte) 0x85}, octets("\r\n"));
        FieldBlock fields = fields(mode, section, lines, 1);
        assertEquals(new String(opaque, StandardCharsets.ISO_8859_1), fields.get("x-opaque"));
        assertArrayEquals(opaque, octets(fields.get("x-opaque")));
        assertEquals("\u0085\u00a0 MiXeD \u00a0\u0085", fields.get("x-edges"));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void colonAndCrlfAreWireDelimitersNotApiCharacters(HttpMessageType mode, Section section) throws Exception {
        FieldBlock fields = fields(mode, section, octets("X:Y: z\r\nX-Other: two\r\nX: final\r\n"), 1);
        assertEquals(List.of("Y: z", "final"), fields.getAll("x"));
        assertEquals("two", fields.get("x-other"));
    }

    static Stream<Arguments> malformedLines() {
        return contexts().flatMap(context -> Stream.of(
            ": value\r\n", "Missing-Colon\r\n", "X \t: value\r\n",
            "X: one\ntwo\r\n", "X: one\rtwo\r\n", "X: one\nX-Other: two\r\n",
            "X: one\rX-Other: two\r\n", "X: one\r\nX-Missing-Colon\r\n",
            "X: one\r\nX-Bad :\r\n")
            .map(line -> Arguments.of(context.get()[0], context.get()[1], line)));
    }

    @ParameterizedTest
    @MethodSource("malformedLines")
    void malformedFieldLinesNeverCompleteSuccessfully(HttpMessageType mode, Section section, String lines) throws Exception {
        assertInvalid(mode, section, octets(lines), 1);
    }

    static Stream<Arguments> truncatedSections() {
        return contexts().flatMap(context -> Stream.of("", "X", "X:", "X: value", "X: value\r",
            "X: value\r\n", "X: value\r\n\r")
            .map(tail -> Arguments.of(context.get()[0], context.get()[1], tail)));
    }

    @ParameterizedTest
    @MethodSource("truncatedSections")
    void truncatedSectionsFailInsteadOfPublishingCleanEof(HttpMessageType mode, Section section, String tail) throws Exception {
        Http1MessageParser parser = parser(mode, concat(prefix(mode, section), octets(tail)), 1, -1);
        if (section == Section.TRAILER) assertInstanceOf(HttpMessageTemp.class, parser.readNext());
        assertProtocolFailure(parser::readNext);
        assertNull(parser.takeTrailers(), "Incomplete trailers must never be published");
    }

    static Stream<Arguments> folds() {
        return contexts().flatMap(context -> Stream.of(
            "X-Fold: one\r\n two\r\n", "X-Fold: one\r\n\ttwo\r\n",
            "X-Fold: one\r\n \ttwo\r\n\t three\r\n", "X-Fold:\r\n two\r\n",
            "X-Fold: \t\r\n\ttwo\r\n")
            .flatMap(line -> IntStream.of(1, 3, 8192).mapToObj(read ->
                Arguments.of(context.get()[0], context.get()[1], line, read))));
    }

    @ParameterizedTest
    @MethodSource("folds")
    void requestsRejectFoldingButResponseParserUnfoldsIt(HttpMessageType mode, Section section, String lines, int readSize) throws Exception {
        if (mode == HttpMessageType.REQUEST) {
            assertInvalid(mode, section, octets(lines), readSize);
        } else {
            FieldBlock fields = fields(mode, section, octets(lines), readSize);
            assertNotNull(fields.get("x-fold"), "Unfolded empty-first-line field must remain present");
            String normalized = fields.get("x-fold").replaceAll("[ \\t]+", " ").trim();
            String expected = lines.contains("three") ? "one two three" : lines.contains("one") ? "one two" : "two";
            assertEquals(expected, normalized);
            assertFalse(fields.get("x-fold").contains("\r"));
            assertFalse(fields.get("x-fold").contains("\n"));
        }
    }

    static Stream<Arguments> forbiddenTrailers() {
        // Independent expected list: framing, routing, authentication and representation metadata.
        return Stream.of(HttpMessageType.REQUEST, HttpMessageType.RESPONSE).flatMap(mode ->
            Stream.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "content-length", "host", "te", "upgrade",
                "content-type", "content-encoding", "content-range", "authorization", "proxy-authorization")
                .flatMap(name -> Stream.of(name, name.toUpperCase(Locale.ROOT),
                    Character.toUpperCase(name.charAt(0)) + name.substring(1)))
                .flatMap(name -> Stream.of("", "value").map(value -> Arguments.of(mode, name, value))));
    }

    @Test
    void forbiddenTrailersAreRejectedEvenWhenEmptyOrMixedCase() {
        for (Arguments args : forbiddenTrailers().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            HttpMessageType mode = (HttpMessageType) values[0];
            String name = (String) values[1];
            String value = (String) values[2];
            assertAll(mode + " trailer " + name + "=" + value,
                () -> assertInvalid(mode, Section.TRAILER, octets("X-Checksum: good\r\n" + name + ":" + value + "\r\n"), 7));
        }
    }

    static Stream<Arguments> fragments() {
        return contexts().flatMap(context -> IntStream.of(1, 3, 8192, Integer.MAX_VALUE)
            .mapToObj(read -> Arguments.of(context.get()[0], context.get()[1], read)));
    }

    @ParameterizedTest
    @MethodSource("fragments")
    void validSectionsEndExactlyBeforeTheSuccessor(HttpMessageType mode, Section section, int readSize) throws Exception {
        String lines = "X-Mixed: First\r\nx-mixed: second\r\nX-Checksum: Digest:MiXeD\r\n";
        assertBoundary(mode, section, octets(lines), readSize, -1, "Digest:MiXeD");
    }

    static Stream<Arguments> exactSplits() {
        return contexts().flatMap(context -> IntStream.range(0, 7).boxed().flatMap(offset ->
            Stream.of(false, true).map(opaque ->
                Arguments.of(context.get()[0], context.get()[1], offset, opaque))));
    }

    @ParameterizedTest
    @MethodSource("exactSplits")
    void colonCrlfAndOpaqueBytesStraddle8192ByteBoundary(HttpMessageType mode, Section section, int offset,
                                                        boolean opaque) throws Exception {
        String first = "X-Pad: ";
        int padding = 8192 - prefix(mode, section).length - first.length() - 2 - "X-Checksum".length() - offset;
        String value = opaque ? "\u0085\u00a0" : "Ab";
        String lines = first + "p".repeat(padding) + "\r\nX-Checksum:\t" + value + "\r\n";
        // Offsets put colon, HTAB, each opaque byte, CR, LF and the following CR at the boundary.
        assertBoundary(mode, section, octets(lines), 8192, 8192, value);
    }

    static Stream<Arguments> exactShortSplits() {
        return contexts().flatMap(context -> IntStream.rangeClosed(1, 20).mapToObj(offset ->
            Arguments.of(context.get()[0], context.get()[1], offset)));
    }

    @ParameterizedTest
    @MethodSource("exactShortSplits")
    void everyShortFieldBoundaryCanBeSplit(HttpMessageType mode, Section section, int offset) throws Exception {
        byte[] lines = octets("X-Checksum: MiXeD\r\n");
        assertBoundary(mode, section, lines, Integer.MAX_VALUE, prefix(mode, section).length + offset, "MiXeD");
    }

    private static byte[] prefix(HttpMessageType mode, Section section) {
        String start = mode == HttpMessageType.REQUEST ? "POST /first HTTP/1.1\r\nHost: localhost\r\n"
            : "HTTP/1.1 200 OK\r\n";
        return octets(start + (section == Section.TRAILER ?
            "X-Initial: kept\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n" : ""));
    }

    private static byte[] suffix(Section section) {
        return octets(section == Section.INITIAL ? "Content-Length: 0\r\n\r\n" : "\r\n");
    }

    private static Http1MessageParser parser(HttpMessageType mode, byte[] wire, int readSize, int split) {
        Queue<HttpRequestTemp> requests = new ConcurrentLinkedQueue<>();
        if (mode == HttpMessageType.RESPONSE) {
            for (int i = 0; i < 2; i++) {
                HttpRequestTemp request = HttpRequestTemp.empty();
                request.setMethod(Method.GET);
                request.setUrl(i == 0 ? "/first" : "/next");
                requests.add(request);
            }
        }
        ByteArrayInputStream input = new ByteArrayInputStream(wire) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int remainingToSplit = split > pos ? split - pos : Integer.MAX_VALUE;
                return super.read(bytes, offset, Math.min(length, Math.min(readSize, remainingToSplit)));
            }
        };
        return new Http1MessageParser(mode, requests, input, 32768, 8192);
    }

    private static FieldBlock fields(HttpMessageType mode, Section section, byte[] lines, int readSize) throws Exception {
        Http1MessageParser parser = parser(mode, concat(prefix(mode, section), lines, suffix(section)), readSize, -1);
        HttpMessageTemp message = assertInstanceOf(HttpMessageTemp.class, parser.readNext());
        if (message instanceof HttpRequestTemp) assertNull(((HttpRequestTemp) message).getRejectRequest());
        FieldBlock fields = message.headers();
        if (section == Section.TRAILER) {
            assertSame(MessageBodyBit.EndOfBodyBit, parser.readNext());
            fields = parser.takeTrailers();
            assertNotNull(fields);
            assertEquals("kept", message.headers().get("x-initial"));
        }
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
        return fields;
    }

    private static void assertInvalid(HttpMessageType mode, Section section, byte[] lines, int readSize) throws Exception {
        byte[] successor = octets(mode == HttpMessageType.REQUEST ?
            "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n" : "HTTP/1.1 204 No Content\r\n\r\n");
        Http1MessageParser parser = parser(mode, concat(prefix(mode, section), lines, suffix(section), successor), readSize, -1);
        if (section == Section.TRAILER) assertInstanceOf(HttpMessageTemp.class, parser.readNext());
        assertProtocolFailure(parser::readNext);
        assertNull(parser.takeTrailers(), "Rejected trailer blocks must not be published");
    }

    private static void assertProtocolFailure(org.junit.jupiter.api.function.Executable read) {
        Exception failure = assertThrows(Exception.class, read);
        assertTrue(failure instanceof ParseException || failure instanceof IOException
            || failure instanceof HttpException || failure instanceof IllegalArgumentException,
            "Unexpected fixture/internal exception: " + failure);
        if (failure instanceof HttpException) assertEquals(400, ((HttpException) failure).status().code());
    }

    private static void assertBoundary(HttpMessageType mode, Section section, byte[] lines, int readSize,
                                       int split, String checksum) throws Exception {
        String next = mode == HttpMessageType.REQUEST ?
            "GET /next HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\nnext" :
            "HTTP/1.1 201 Created\r\nContent-Length: 4\r\n\r\nnext";
        Http1MessageParser parser = parser(mode, concat(prefix(mode, section), lines, suffix(section), octets(next)), readSize, split);
        HttpMessageTemp first = assertInstanceOf(HttpMessageTemp.class, parser.readNext());
        if (first instanceof HttpRequestTemp) assertNull(((HttpRequestTemp) first).getRejectRequest());
        FieldBlock fields = first.headers();
        if (section == Section.TRAILER) {
            assertSame(MessageBodyBit.EndOfBodyBit, parser.readNext());
            fields = parser.takeTrailers();
            assertNotNull(fields);
            assertEquals("kept", first.headers().get("x-initial"));
            assertNull(first.headers().get("x-checksum"));
            assertNull(parser.takeTrailers());
        }
        assertEquals(checksum, fields.get("x-checksum"));
        if (fields.contains("x-mixed")) assertEquals(List.of("First", "second"), fields.getAll("x-mixed"));
        HttpMessageTemp successor = assertInstanceOf(HttpMessageTemp.class, parser.readNext());
        if (mode == HttpMessageType.REQUEST) {
            assertEquals("/next", ((HttpRequestTemp) successor).getUrl());
            assertNull(((HttpRequestTemp) successor).getRejectRequest());
        } else {
            assertEquals(201, ((HttpResponseTemp) successor).getStatusCode());
            assertEquals("/first", ((HttpResponseTemp) first).getRequest().getUrl());
            assertEquals("/next", ((HttpResponseTemp) successor).getRequest().getUrl());
        }
        assertNull(successor.headers().get("x-checksum"));
        assertNull(parser.takeTrailers());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) {
            MessageBodyBit bit = assertInstanceOf(MessageBodyBit.class, parser.readNext());
            assertNotSame(MessageBodyBit.EOFMsg, bit);
            body.write(bit.bytes(), bit.offset(), bit.length());
            if (bit.isLast()) break;
        }
        assertEquals("next", body.toString(StandardCharsets.US_ASCII));
        assertSame(MessageBodyBit.EOFMsg, parser.readNext());
        assertNull(parser.takeTrailers());
    }
}
