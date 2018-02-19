package io.muserver.rest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.muserver.Mutils.urlDecode;

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
            value = value.replace(octet, urlDecode(octet));
        }
    }

    private Jaxutils() {
    }
}
