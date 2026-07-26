package io.muserver.rest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class FormUrlEncoder {

    static String formUrlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private FormUrlEncoder() {
    }
}
