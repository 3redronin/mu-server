package io.muserver;

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.TOKEN;
import static io.muserver.FieldConformanceFixtures.octets;
import static io.muserver.ForwardedHeaderTest.fwd;
import static io.muserver.HeaderNamesTest.sequences;
import static java.util.Arrays.asList;
import static java.util.Collections.*;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.*;

public class Http1AndHttp2HeadersTest {

    private final Headers[] impls = { new FieldBlock() };

    @Test
    public void caseIsInsensitive() {
        for (Headers headers : impls) {
            headers.set("Header", "1");
            headers.add("hEader", "2");
            headers.add((CharSequence)("heAder"), "3");
            assertThat(headers.get("heaDer"), is("1"));
            assertThat(headers.get("headEr", "blah"), is("1"));
            assertThat(headers.getAll("headeR"), contains("1", "2", "3"));
            assertThat(headers.contains("HEader"), is(true));
            assertThat(headers.contains("HEAder", "1", false), is(true));
            assertThat(headers.containsValue("HEAder", "1", false), is(true));
            assertThat(headers.names(), contains("header"));
            assertThat(headers.size(), is(3));

            assertThat(headers.isEmpty(), is(false));
            for (Map.Entry<String, String> header : headers) {
                assertThat(header.getKey().toLowerCase(), is("header"));
            }
        }
    }


    @Test
    public void invalidHeaderNamesAreRejected() {
        for (Headers headers : impls) {
            assertThrows(IllegalArgumentException.class, () -> headers.set("Bad Header", "value"));
            assertThrows(IllegalArgumentException.class, () -> headers.add("bad:header", "value"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\u0000", "\u0001", "\u007f"})
    public void invalidHeaderValuesAreRejected(String forbidden) {
        for (Headers headers : impls) {
            String value = "a" + forbidden + "b";
            assertThrows(IllegalArgumentException.class, () -> headers.set("x-test", value));
            assertThrows(IllegalArgumentException.class, () -> headers.add("x-test", asList("ok", value)));
            assertThrows(IllegalArgumentException.class, () -> headers.set("x-test", asList("ok", value)));
        }
    }

    @Test
    public void horizontalTabsAreAllowedInFieldValues() {
        Headers headers = Headers.create().set("x-test", "a\tb");
        assertThat(headers.get("x-test"), equalTo("a\tb"));
    }

    @Test
    void publicNamesUseTheFullAsciiTokenGrammar() {
        for (int code = 0; code < 256; code++) {
            int octet = code;
            assertAll("public name octet " + octet, () -> assertPublicNameOctet(octet));
        }
    }

    private static void assertPublicNameOctet(int code) {
        String character = Character.toString((char) code);
        assertAll(Stream.of(character, character + "Name", "Na" + character + "me", "Name" + character)
            .map(name -> () -> {
                if (TOKEN.indexOf(code) >= 0) {
                    Headers headers = Headers.create().add(name, "First").add(name, List.of("Second", "Third"));
                    String expected = name.toLowerCase(Locale.ROOT);
                    assertEquals(List.of("First", "Second", "Third"), headers.getAll(expected));
                    assertEquals(Set.of(expected), headers.names());
                    headers.set(name, "Replacement");
                    assertEquals(List.of("Replacement"), headers.getAll(expected));
                } else {
                    assertInvalidName(name);
                }
            }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " name", "name ", "\tname", "name\t", "\u0100", "\u0130",
        "\u017f", "\u212a", "lin\u212a", "\u212aey", "\u4e2d", "\ud83d\ude00", "\ud800", "\udc00",
        "x\ud800y", "x\udc00y"})
    void allPublicNameEntryPointsRejectInvalidNames(String name) {
        assertAll(sequences(name).stream().map(input -> () -> assertInvalidName(input)));
    }

    private static void assertInvalidName(CharSequence name) {
        Headers headers = Headers.create().set("link", "Original");
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> headers.add(name, "value")),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.set(name, "value")),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.add(name, List.of("value"))),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.set(name, List.of("value"))),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.get(name)),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.getAll(name)),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.contains(name)),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.contains(name, "Original", false)),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.containsValue(name, "Original", false)),
            () -> assertThrows(IllegalArgumentException.class, () -> headers.remove(name))
        );
    }

    @ParameterizedTest(name = "{0} via {1}")
    @MethodSource("nameSpellings")
    void allCharSequencesNormalizeAcrossMutationLookupAndRemoval(String spelling, String kind) {
        String expected = spelling.toLowerCase(Locale.ROOT);
        Headers headers = Headers.create();
        CharSequence input = sequence(spelling, kind);
        headers.add(input, "First");
        headers.add(input, List.of("Second", "Third"));
        assertAll(
            () -> assertEquals(Set.of(expected), headers.names()),
            () -> assertEquals(List.of(expected, expected, expected),
                headers.entries().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toList())),
            () -> assertAll(sequences(spelling.toUpperCase(Locale.ROOT)).stream().map(query -> () -> {
                assertEquals("First", headers.get(query));
                assertEquals("First", headers.get(query, "Fallback"));
                assertEquals(List.of("First", "Second", "Third"), headers.getAll(query));
                assertTrue(headers.contains(query));
                assertTrue(headers.contains(query, "First", false));
                assertFalse(headers.contains(query, "first", false));
                assertTrue(headers.contains(query, "first", true));
                assertTrue(headers.containsValue(query, "Second", false));
            }))
        );
        headers.set(input, "Replacement");
        assertEquals(List.of("Replacement"), headers.getAll(expected));
        headers.set(input, List.of("Fourth", "Fifth"));
        assertEquals(List.of("Fourth", "Fifth"), headers.getAll(expected));
        headers.remove(sequence(spelling.toUpperCase(Locale.ROOT), kind));
        assertTrue(headers.isEmpty());
        assertFalse(headers.contains(input));
        assertNull(headers.get(input));
        assertEquals("Fallback", headers.get(input, "Fallback"));
        assertEquals(List.of(), headers.getAll(input));
        headers.set(input, "Removed by null").set(input, (Object) null);
        assertTrue(headers.isEmpty());
    }

    static Stream<Arguments> nameSpellings() {
        return Stream.of("content-type", "ConTent-Type", "CONTENT-TYPE",
            "x-custom-token", "X-Custom-Token", "X-CUSTOM-TOKEN")
            .flatMap(name -> Stream.of("String", "StringBuilder", "CharBuffer")
                .map(kind -> Arguments.of(name, kind)));
    }

    private static CharSequence sequence(String value, String kind) {
        switch (kind) {
            case "StringBuilder": return new StringBuilder(value);
            case "CharBuffer": return java.nio.CharBuffer.wrap(value);
            default: return value;
        }
    }

    @Test
    void valuesClassifyEveryOctetAtEdgesAndInTheInterior() {
        for (int code = 0; code < 256; code++) {
            int octet = code;
            assertAll("value octet " + octet, () -> assertValueOctet(octet));
        }
    }

    private static void assertValueOctet(int code) {
        String c = Character.toString((char) code);
        if ((code < 32 && code != 9) || code == 127) {
            // Selected strict policy: only HTAB is allowed among control octets.
            assertAll(Stream.of(c, c + "Value", "Va" + c + "lue", "Value" + c,
                " \t" + c + "Value\t ", " \tValue" + c + "\t ", "Value \t" + c + "\t Text",
                " \t" + c + "\t ").map(value -> () -> assertRejectedValue(value)));
        } else {
            String edge = code == 9 || code == 32 ? "" : c;
            assertAll(
                () -> assertAcceptedValue(c, edge),
                () -> assertAcceptedValue(c + "Value", edge + "Value"),
                () -> assertAcceptedValue("Value" + c, "Value" + edge),
                () -> assertAcceptedValue("Va" + c + "lue", "Va" + c + "lue"),
                () -> assertAcceptedValue(" \t" + c + "Value" + c + "\t ", edge + "Value" + edge)
            );
        }
    }

    @ParameterizedTest
    @MethodSource("whitespaceValues")
    void onlyOuterSpaceAndHorizontalTabAreTrimmed(String input, String expected) {
        assertAcceptedValue(input, expected);
    }

    static Stream<Arguments> whitespaceValues() {
        // Selected API policy; wire decoders must not apply this trimming.
        return Stream.of(
            Arguments.of("", ""), Arguments.of(" ", ""), Arguments.of("\t", ""),
            Arguments.of(" \t \t", ""), Arguments.of(" \tMiXeD: !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~\t ",
                "MiXeD: !\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"),
            Arguments.of(" \tOne  Two\t\tThree \tFour\t ", "One  Two\t\tThree \tFour"),
            Arguments.of("\u0085Value\u0085", "\u0085Value\u0085"),
            Arguments.of("\u00a0Value\u00a0", "\u00a0Value\u00a0"),
            Arguments.of(" \t\u0085\u00a0Value\u00a0\u0085\t ", "\u0085\u00a0Value\u00a0\u0085"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0100", "\u0130", "\u017f", "\u212a", "\u2003", "\u2028",
        "\u4e2d", "\uffff", "\ud83d\ude00", "\ud800", "\udc00", "\ud800x", "x\udc00"})
    void valuesOutsideLatin1CannotBeSilentlyReplaced(String invalid) {
        assertAll(Stream.of(invalid, invalid + "Value", "Va" + invalid + "lue", "Value" + invalid,
            " \t" + invalid + "\t ").map(value -> () -> assertRejectedValue(value)));
    }

    private static List<Object> valueInputs(String value) {
        return List.of(value, new StringBuilder(value), java.nio.CharBuffer.wrap(value), new Object() {
            @Override
            public String toString() {
                return value;
            }
        });
    }

    private static void assertRejectedValue(String value) {
        assertAll(valueInputs(value).stream().map(input -> () -> assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> Headers.create().add("x-value", input)),
            () -> assertThrows(IllegalArgumentException.class, () -> Headers.create().set("x-value", input)),
            () -> assertThrows(IllegalArgumentException.class,
                () -> Headers.create().add("x-value", List.of("Valid", input))),
            () -> assertThrows(IllegalArgumentException.class,
                () -> Headers.create().set("x-value", List.of("Valid", input)))
        )));
    }

    private static void assertAcceptedValue(String input, String expected) {
        assertAll(valueInputs(input).stream().map(value -> () -> {
            Headers added = Headers.create().add("X-Value", value);
            Headers set = Headers.create().set("X-Value", value);
            Headers bulkAdded = Headers.create().add("X-Value", List.of(value, "Second"));
            Headers bulkSet = Headers.create().set("X-Value", List.of(value, "Second"));
            assertAll(
                () -> assertStoredValues(added, List.of(expected)),
                () -> assertStoredValues(set, List.of(expected)),
                () -> assertStoredValues(bulkAdded, List.of(expected, "Second")),
                () -> assertStoredValues(bulkSet, List.of(expected, "Second"))
            );
        }));
    }

    private static void assertStoredValues(Headers headers, List<String> expected) {
        assertTrue(headers.contains("x-value"), "Even empty values must remain present");
        assertEquals(expected.size(), headers.size());
        assertAll(
            () -> assertEquals(expected.get(0), headers.get("x-value")),
            () -> assertEquals(expected, headers.getAll("x-value")),
            () -> assertAll(IntStream.range(0, expected.size()).mapToObj(i -> () ->
                assertArrayEquals(octets(expected.get(i)), ((FieldLine) headers.entries().get(i)).value().bytes)))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"add", "set", "setAll"})
    void copyingPreservesNormalizedNamesValueOrderEmptyAndOpaqueValues(String operation) {
        String opaque = IntStream.range(128, 256).collect(StringBuilder::new,
            (builder, code) -> builder.append((char) code), StringBuilder::append).toString();
        Headers source = Headers.create()
            .add("X-Value", List.of(" \tMiXeD\t ", "", opaque, "Second"))
            .set("CONTENT-TYPE", "Application/Example");
        Headers target = Headers.create().set("x-value", "Old").set("x-retained", "Keep");
        switch (operation) {
            case "add": target.add(source); break;
            case "set": target.set(source); break;
            default: target.setAll(source);
        }
        List<String> expected = operation.equals("add")
            ? List.of("Old", "MiXeD", "", opaque, "Second") : List.of("MiXeD", "", opaque, "Second");
        assertAll(
            () -> assertEquals(expected, target.getAll("X-VALUE")),
            () -> assertEquals("Application/Example", target.get("Content-Type")),
            () -> assertEquals(operation.equals("set") ? null : "Keep", target.get("x-retained")),
            () -> assertEquals(operation.equals("set") ? Set.of("x-value", "content-type")
                : Set.of("x-value", "content-type", "x-retained"), target.names()),
            () -> assertEquals(List.of("MiXeD", "", opaque, "Second"), source.getAll("x-value")),
            () -> assertArrayEquals(octets(opaque), ((FieldLine) target.entries().stream()
                .filter(entry -> entry.getKey().equals("x-value"))
                .skip(operation.equals("add") ? 3 : 2).findFirst().orElseThrow()).value().bytes)
        );
        target.remove(new StringBuilder("X-VALUE"));
        assertFalse(target.contains("x-value"));
        assertEquals(List.of("MiXeD", "", opaque, "Second"), source.getAll("x-value"));
    }

    @Test
    public void canParsePrimitives() {
        for (Headers headers : impls) {
            headers.set("decimal", "1.234");
            headers.set("int", "1234");
            headers.set("bool", "true");
            headers.set("nobool", "false");

            assertThat(headers.getFloat("decimal", 1.3f), is(1.234f));
            assertThat(headers.getFloat("decimaldewey", 1.3f), is(1.3f));
            assertThat(headers.getDouble("decimal", 1.3), is(1.234));
            assertThat(headers.getDouble("decimaldewey", 1.3), is(1.3));

            assertThat(headers.getInt("int", 123456789), is(1234));
            assertThat(headers.getInt("clint", 123456789), is(123456789));
            assertThat(headers.getLong("int", 123456789L), is(1234L));
            assertThat(headers.getLong("clint", 123456789L), is(123456789L));

            assertThat(headers.getBoolean("bool"), is(true));
            assertThat(headers.getBoolean("nobool"), is(false));
            assertThat(headers.getBoolean("reallynobool"), is(false));
        }
    }

    @Test
    public void acceptHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.accept(), equalTo(emptyList()));

            headers.set("Accept", "text/html,application/xhtml+xml,application/xml ; q=0.9,image/webp,*/*;q=0.8");
            assertThat(headers.accept(), contains(
                ph("text/html"),
                ph("application/xhtml+xml"),
                ph("application/xml", "q", "0.9"),
                ph("image/webp"),
                ph("*/*", "q", "0.8")
            ));
        }
    }

    @Test
    public void acceptCharsetHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptCharset(), equalTo(emptyList()));

            headers.set("Accept-Charset", "iso-8859-5, unicode-1-1;q=0.8");
            assertThat(headers.acceptCharset(), contains(
                ph("iso-8859-5"),
                ph("unicode-1-1", "q", "0.8")
            ));
        }
    }

    @Test
    public void acceptEncodingHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptEncoding(), equalTo(emptyList()));

            headers.set("Accept-Encoding", "compress, gzip");
            assertThat(headers.acceptEncoding(), contains(
                ph("compress"),
                ph("gzip")
            ));

            headers.set("Accept-Encoding", "*");
            assertThat(headers.acceptEncoding(), contains(
                ph("*")
            ));

            headers.set("Accept-Encoding", "compress;q=0.5, gzip;q=1.0");
            assertThat(headers.acceptEncoding(), contains(
                ph("compress", "q", "0.5"),
                ph("gzip", "q", "1.0")
            ));

            headers.set("Accept-Encoding", "gzip;q=1.0, identity; q=0.5, *;q=0");
            assertThat(headers.acceptEncoding(), contains(
                ph("gzip", "q", "1.0"),
                ph("identity", "q", "0.5"),
                ph("*", "q", "0")
            ));
        }
    }

    @Test
    public void acceptLanguageHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.acceptLanguage(), equalTo(emptyList()));

            headers.set("Accept-Language", "da, en-gb;q=0.8, en;q=0.7");
            assertThat(headers.acceptLanguage(), contains(
                ph("da"),
                ph("en-gb", "q", "0.8"),
                ph("en", "q", "0.7")
            ));
        }
    }

    @Test
    public void cacheControlHeaderCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.cacheControl().parameters(), equalTo(emptyMap()));

            headers.set("Cache-Control", "max-age=60");
            assertThat(headers.cacheControl().parameters(), equalTo(singletonMap("max-age", "60")));
            headers.set("Cache-Control", "private, community=\"UCI\"");
            assertThat(headers.cacheControl().parameters().keySet(), contains("private", "community"));
            assertThat(headers.cacheControl().parameter("community"), equalTo("UCI"));
        }
    }

    @Test
    public void contentTypeCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.contentType(), is(nullValue()));

            headers.set("Content-Type", "text/html; charset=ISO-8859-4");
            assertThat(headers.contentType(), equalTo(new MediaType("text", "html", "ISO-8859-4")));
        }
    }

    @Test
    public void forwardedHeadersCanBeParsed() {
        for (Headers headers : impls) {
            assertThat(headers.forwarded(), equalTo(emptyList()));

            headers.set("Forwarded", "for=192.0.2.43");
            assertThat(headers.forwarded(), contains(
                new ForwardedHeader(null, "192.0.2.43", null, null, null)
            ));

            headers.set("X-Forwarded-For", "1.2.3.4"); // ignored as there is a Forwarded header
            headers.set("Forwarded", "for=192.0.2.43," +
                "      for=198.51.100.17;by=203.0.113.60;proto=http;host=example.com");
            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", null, null),
                fwd("203.0.113.60", "198.51.100.17", "example.com", "http")
            ));
        }
    }

    @Test
    public void ifNoForwardedHeaderThenXForwardedIsUsed() {
        for (Headers headers : impls) {
            headers.set("X-Forwarded-For", asList("192.0.2.43", "2001:db8:cafe::17"));
            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", null, null),
                fwd(null, "2001:db8:cafe::17", null, null)
            ));
            assertThat(headers.forwarded().get(1).toString(), equalTo("for=\"2001:db8:cafe::17\""));

            headers.clear();
            headers.set("X-Forwarded-Host", asList("example.org", "internal.example.org"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org", null),
                fwd(null, null, "internal.example.org", null)
            ));

            headers.clear();
            headers.set("X-Forwarded-Host", asList("example.org", "internal.example.org"));
            headers.set("X-Forwarded-Port", asList("80", "8088"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org:80", null),
                fwd(null, null, "internal.example.org:8088", null)
            ));

            headers.clear();
            headers.set("X-Forwarded-Proto", asList("http", "https"));
            assertThat(headers.forwarded(), contains(
                fwd(null, null, null, "http"),
                fwd(null, null, null, "https")
            ));
        }
    }

    @Test
    public void ifMultipleXForwardedHeadersHaveSameLengthsThenAllUsed() {
        for (Headers headers : impls) {
            headers.add("X-Forwarded-For", "192.0.2.43");
            headers.add("X-Forwarded-Host", "example.org");
            headers.add("X-Forwarded-Proto", "https");

            headers.add("X-Forwarded-Proto", "http");
            headers.add("X-Forwarded-For", "10.0.0.0");
            headers.add("X-Forwarded-Host", "internal.example.org");

            assertThat(headers.forwarded(), contains(
                fwd(null, "192.0.2.43", "example.org", "https"),
                fwd(null, "10.0.0.0", "internal.example.org", "http")
            ));
        }
    }

    @Test
    public void ifSomeXForwardedHeadersHaveLessValuesThanOthersThenTheyAreIgnored() {
        for (Headers headers : impls) {
            headers.add("X-Forwarded-For", "192.0.2.43");
            headers.add("X-Forwarded-Host", "example.org");
            headers.add("X-Forwarded-Proto", "https");

            headers.add("X-Forwarded-Proto", "http");
            headers.add("X-Forwarded-Host", "internal.example.org");

            assertThat(headers.forwarded(), contains(
                fwd(null, null, "example.org", "https"),
                fwd(null, null, "internal.example.org", "http")
            ));
        }
    }

    private static ParameterizedHeaderWithValue ph(String value) {
        return new ParameterizedHeaderWithValue(value, emptyMap());
    }

    private static ParameterizedHeaderWithValue ph(String value, String paramName, String paramValue) {
        return new ParameterizedHeaderWithValue(value, Collections.singletonMap(paramName, paramValue));
    }

}
