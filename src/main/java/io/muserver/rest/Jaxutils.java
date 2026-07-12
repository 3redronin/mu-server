package io.muserver.rest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

class Jaxutils {

    private static final Pattern encoded = Pattern.compile(".*(?<octet>%[0-9A-F][0-9A-F]).*");

    static String leniantUrlDecode(String value) {
        if (!value.contains("%")) {
            return value;
        }
        while (true) {
            Matcher matcher = encoded.matcher(value);
            if (!matcher.matches()) {
                return value;
            }
            String octet = matcher.group("octet");
            value = value.replace(octet, uriDecode(octet));
        }
    }

    static String uriDecode(String value) {
        int firstPercent = value.indexOf('%');
        if (firstPercent < 0) return value;
        var decoded = new StringBuilder(value.length()).append(value, 0, firstPercent);
        var bytes = new ByteArrayOutputStream();
        for (int i = firstPercent; i < value.length();) {
            if (value.charAt(i) != '%') {
                decoded.append(value.charAt(i++));
                continue;
            }
            bytes.reset();
            while (i < value.length() && value.charAt(i) == '%') {
                if (i + 2 >= value.length()) throw invalid(value, null);
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw invalid(value, null);
                bytes.write((high << 4) | low);
                i += 3;
            }
            try {
                decoded.append(UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException e) {
                throw invalid(value, e);
            }
        }
        return decoded.toString();
    }

    private static IllegalArgumentException invalid(String value, Exception cause) {
        return new IllegalArgumentException("Invalid UTF-8 percent encoding in " + value, cause);
    }

    private Jaxutils() {
    }
}
