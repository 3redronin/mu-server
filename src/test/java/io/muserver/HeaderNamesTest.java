package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.CharBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.TOKEN;
import static io.muserver.FieldConformanceFixtures.octets;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.*;

class HeaderNamesTest {

    @Test
    void allBuiltInAreLowercase() {
        for (Map.Entry<String, ValidatedHeaderName> entry : HeaderNames.builtIn.entrySet()) {
            var name = entry.getKey().toString();
            assertThat(name.toLowerCase(Locale.ROOT), equalTo(name));
            HeaderString hs = entry.getValue();
            assertThat(hs.toString(), sameInstance(name));
        }
    }

    @Test
    void lookupsGiveSameInstances() {
        var lookedUpByString = HeaderString.valueOf("ConTent-Type", HeaderString.Type.HEADER);
        var lookedUpByStringBuilder = HeaderString.valueOf(new StringBuilder("content-type"), HeaderString.Type.HEADER);
        var lookedUpByStringHeaderStringValueOf = HeaderString.valueOf("CONTENT-TYPE", HeaderString.Type.HEADER);
        var constant = HeaderNames.CONTENT_TYPE;
        assertThat(lookedUpByString, sameInstance(constant));
        assertThat(lookedUpByStringBuilder, sameInstance(constant));
        assertThat(lookedUpByStringHeaderStringValueOf, sameInstance(constant));
        assertThat(HeaderNames.findBuiltIn("content-type"), sameInstance(constant));
    }

    @Test
    void applicationHeadersTrustValidatedNamesButCheckRawNames() {
        var fields = new FieldBlock();
        var builtIn = (ValidatedHeaderName) HeaderNames.CONTENT_TYPE;
        fields.add(HeaderNames.CONTENT_TYPE, "text/plain");
        assertSame(builtIn, fields.lineIterator().iterator().next().name());

        HeaderString rawCustom = HeaderString.valueOf(octets("x-custom"), HeaderString.Type.HEADER);
        assertFalse(rawCustom instanceof ValidatedHeaderName);
        fields.add(rawCustom, "value");
        var lines = fields.lineIterator().iterator();
        lines.next();
        assertInstanceOf(ValidatedHeaderName.class, lines.next().name());
        assertThrows(IllegalArgumentException.class,
            () -> fields.add(HeaderString.valueOf(octets("bad name"), HeaderString.Type.HEADER), "value"));
    }

    @Test
    void allBuiltInsHaveCanonicalApplicationLookupForEveryCharSequence() {
        assertAll(HeaderNames.builtIn.entrySet().stream().map(entry -> () -> {
            String lower = entry.getKey().toString();
            assertSame(entry.getValue(), HeaderNames.findBuiltIn(lower));
            for (String spelling : List.of(lower, lower.toUpperCase(Locale.ROOT),
                Character.toUpperCase(lower.charAt(0)) + lower.substring(1))) {
                for (CharSequence input : sequences(spelling)) {
                    assertSame(entry.getValue(), HeaderString.valueOf(input, HeaderString.Type.HEADER), spelling);
                }
            }
        }));
    }

    @Test
    void applicationNamesUseTheFullAsciiTokenGrammar() {
        for (int code = 0; code < 256; code++) {
            int octet = code;
            assertAll("application name octet " + octet, () -> assertApplicationNameOctet(octet));
        }
    }

    private static void assertApplicationNameOctet(int code) {
        String character = Character.toString((char) code);
        assertAll(Stream.of(character, character + "name", "na" + character + "me", "name" + character)
            .flatMap(name -> sequences(name).stream())
            .map(input -> () -> {
                if (TOKEN.indexOf(code) >= 0) {
                    String expected = input.toString().toLowerCase(Locale.ROOT);
                    HeaderString actual = HeaderString.valueOf(input, HeaderString.Type.HEADER);
                    assertEquals(expected, actual.toString());
                    assertArrayEquals(octets(expected), actual.bytes);
                } else {
                    assertThrows(IllegalArgumentException.class,
                        () -> HeaderString.valueOf(input, HeaderString.Type.HEADER));
                }
            }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " name", "name ", "\tname", "name\t", "\u0100", "\u0130",
        "\u017f", "\u212a", "lin\u212a", "\u212aey", "\u4e2d", "\ud83d\ude00", "\ud800", "\udc00",
        "x\ud800y", "x\udc00y"})
    void invalidNamesCannotBeRepairedByTrimmingEncodingOrUnicodeCaseFolding(String name) {
        assertAll(sequences(name).stream().map(input -> () ->
            assertThrows(IllegalArgumentException.class,
                () -> HeaderString.valueOf(input, HeaderString.Type.HEADER))));
    }

    static List<CharSequence> sequences(String value) {
        return List.of(value, new StringBuilder(value), CharBuffer.wrap(value));
    }
}
