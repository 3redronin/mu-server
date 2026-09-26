package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.IntStream;

import static io.muserver.FieldConformanceFixtures.octets;
import static org.junit.jupiter.api.Assertions.*;

class HeaderStringTest {

    @ParameterizedTest
    @ValueSource(strings = {"content-type", "Content-Type", "CONTENT-TYPE", "CONTENT-LENGTH",
        "X-Custom-Field", "x-custom-field"})
    void rawNamesPreserveCaseEvenForKnownNames(String name) {
        assertRepresentation(name, HeaderString.valueOf(octets(name), HeaderString.Type.HEADER));
    }

    @ParameterizedTest
    @EnumSource(HeaderString.Type.class)
    void rawRepresentationPreservesEveryOctetBeforeHttpValidation(HeaderString.Type type) {
        String allOctets = IntStream.range(0, 256).collect(StringBuilder::new,
            (builder, code) -> builder.append((char) code), StringBuilder::append).toString();
        assertRepresentation(allOctets, HeaderString.valueOf(octets(allOctets), type));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", " \tMiXeD\t ", "\rValue\n", "\u0000Value\u007f",
        "\u0085Value\u00a0", "\u00ff\u0080"})
    void rawValuesDoNotInvokeApplicationTrimmingOrRejection(String value) {
        assertRepresentation(value, HeaderString.valueOf(octets(value), HeaderString.Type.VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", ":bad:name", "bad/name", "\u0000", "\r\n",
        "\u0080", "\u00ff"})
    void rawNamesDoNotInvokeApplicationGrammarValidation(String name) {
        assertRepresentation(name, HeaderString.valueOf(octets(name), HeaderString.Type.HEADER));
    }

    @Test
    void applicationAndWireNamesHaveDeliberatelyDifferentNormalization() {
        assertAll(
            () -> assertRepresentation("content-type",
                HeaderString.valueOf("Content-Type", HeaderString.Type.HEADER)),
            () -> assertRepresentation("Content-Type",
                HeaderString.valueOf(octets("Content-Type"), HeaderString.Type.HEADER)),
            () -> assertRepresentation("x-custom",
                HeaderString.valueOf("X-Custom", HeaderString.Type.HEADER)),
            () -> assertRepresentation("X-Custom",
                HeaderString.valueOf(octets("X-Custom"), HeaderString.Type.HEADER))
        );
    }

    @Test
    void applicationAndWireValuesHaveDeliberatelyDifferentWhitespacePolicies() {
        assertAll(
            () -> assertRepresentation("MiXeD",
                HeaderString.valueOf(" \tMiXeD\t ", HeaderString.Type.VALUE)),
            () -> assertRepresentation(" \tMiXeD\t ",
                HeaderString.valueOf(octets(" \tMiXeD\t "), HeaderString.Type.VALUE)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> HeaderString.valueOf("A\r\nB", HeaderString.Type.VALUE)),
            () -> assertRepresentation("A\r\nB",
                HeaderString.valueOf(octets("A\r\nB"), HeaderString.Type.VALUE))
        );
    }

    @Test
    void validatedApplicationValuesAreReusedWithoutTrustingWireValues() {
        var fields = new FieldBlock();
        fields.add(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED);
        assertSame(HeaderValues.CHUNKED, fields.lineIterator().iterator().next().value());
        assertSame(ContentTypes.TEXT_PLAIN_UTF8,
            HeaderString.valueOf(ContentTypes.TEXT_PLAIN_UTF8, HeaderString.Type.VALUE));

        HeaderString raw = HeaderString.valueOf(octets("chunked"), HeaderString.Type.VALUE);
        assertFalse(raw instanceof ValidatedHeaderValue);
        fields.add(HeaderNames.TRANSFER_ENCODING, raw);
        var lines = fields.lineIterator().iterator();
        lines.next();
        assertInstanceOf(ValidatedHeaderValue.class, lines.next().value());
        assertThrows(IllegalArgumentException.class, () -> fields.add(HeaderNames.TRANSFER_ENCODING,
            HeaderString.valueOf(octets("bad\rvalue"), HeaderString.Type.VALUE)));
    }

    @Test
    void emptyValueIsAlreadyValidated() {
        assertSame(ValidatedHeaderValue.EMPTY_VALUE, HeaderString.valueOf("", HeaderString.Type.VALUE));
        assertSame(ValidatedHeaderValue.EMPTY_VALUE, HeaderString.valueOf(new byte[0], HeaderString.Type.VALUE));
    }

    private static void assertRepresentation(String expected, HeaderString actual) {
        assertAll(
            () -> assertEquals(expected.length(), actual.length()),
            () -> assertArrayEquals(octets(expected), actual.bytes),
            () -> assertEquals(expected, actual.toString()),
            () -> assertEquals(expected, actual.subSequence(0, actual.length()).toString()),
            () -> assertArrayEquals(expected.chars().toArray(), actual.chars().toArray()),
            () -> assertArrayEquals(expected.codePoints().toArray(), actual.codePoints().toArray()),
            () -> assertAll(IntStream.range(0, expected.length()).mapToObj(i -> () ->
                assertEquals((int) expected.charAt(i), (int) actual.charAt(i), "Unsigned octet at index " + i)))
        );
    }
}
