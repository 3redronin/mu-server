package io.muserver.rest;

import io.muserver.MuException;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

final class FormUrlEncoder {

    static String formUrlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new MuException("Error encoding " + value, e);
        }
    }

    private FormUrlEncoder() {
    }
}
