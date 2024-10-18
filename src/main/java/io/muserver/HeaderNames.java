package io.muserver;


import java.util.HashMap;
import java.util.Map;

/**
 * <p>Standard HTTP header names.</p>
 * <p>These are all defined as lowercase to support HTTP/2 requirements while also not
 * violating HTTP/1.x requirements.  New header names should always be lowercase.</p>
 */
public final class HeaderNames {

    static final Map<CharSequence, HeaderString> builtIn = new HashMap<>();

    private static HeaderString builtInHeader(String name) {
        var hs = new HeaderString(name);
        builtIn.put(hs.toString(), hs);
        return hs;
    }

    /**
     * {@code "accept"}
     */
    public static final CharSequence ACCEPT = builtInHeader("accept");

    /**
     * {@code "accept-charset"}
     */
    public static final CharSequence ACCEPT_CHARSET = builtInHeader("accept-charset");
    /**
     * {@code "accept-encoding"}
     */
    public static final CharSequence ACCEPT_ENCODING = builtInHeader("accept-encoding");
    /**
     * {@code "accept-language"}
     */
    public static final CharSequence ACCEPT_LANGUAGE = builtInHeader("accept-language");
    /**
     * {@code "accept-ranges"}
     */
    public static final CharSequence ACCEPT_RANGES = builtInHeader("accept-ranges");
    /**
     * {@code "accept-patch"}
     */
    public static final CharSequence ACCEPT_PATCH = builtInHeader("accept-patch");
    /**
     * {@code "access-control-allow-credentials"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_CREDENTIALS = builtInHeader("access-control-allow-credentials");
    /**
     * {@code "access-control-allow-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_HEADERS = builtInHeader("access-control-allow-headers");
    /**
     * {@code "access-control-allow-methods"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_METHODS = builtInHeader("access-control-allow-methods");
    /**
     * {@code "access-control-allow-origin"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_ORIGIN = builtInHeader("access-control-allow-origin");
    /**
     * {@code "access-control-expose-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_EXPOSE_HEADERS = builtInHeader("access-control-expose-headers");
    /**
     * {@code "access-control-max-age"}
     */
    public static final CharSequence ACCESS_CONTROL_MAX_AGE = builtInHeader("access-control-max-age");
    /**
     * {@code "access-control-request-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_HEADERS = builtInHeader("access-control-request-headers");
    /**
     * {@code "access-control-request-method"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_METHOD = builtInHeader("access-control-request-method");
    /**
     * {@code "age"}
     */
    public static final CharSequence AGE = builtInHeader("age");
    /**
     * {@code "allow"}
     */
    public static final CharSequence ALLOW = builtInHeader("allow");
    /**
     * {@code "authorization"}
     */
    public static final CharSequence AUTHORIZATION = builtInHeader("authorization");
    /**
     * {@code "cache-control"}
     */
    public static final CharSequence CACHE_CONTROL = builtInHeader("cache-control");
    /**
     * {@code "connection"}
     */
    public static final CharSequence CONNECTION = builtInHeader("connection");
    /**
     * {@code "content-base"}
     */
    public static final CharSequence CONTENT_BASE = builtInHeader("content-base");
    /**
     * {@code "content-encoding"}
     */
    public static final CharSequence CONTENT_ENCODING = builtInHeader("content-encoding");
    /**
     * {@code "content-language"}
     */
    public static final CharSequence CONTENT_LANGUAGE = builtInHeader("content-language");
    /**
     * {@code "content-length"}
     */
    public static final CharSequence CONTENT_LENGTH = builtInHeader("content-length");
    /**
     * {@code "content-location"}
     */
    public static final CharSequence CONTENT_LOCATION = builtInHeader("content-location");
    /**
     * {@code "content-transfer-encoding"}
     */
    public static final CharSequence CONTENT_TRANSFER_ENCODING = builtInHeader("content-transfer-encoding");
    /**
     * {@code "content-disposition"}
     */
    public static final CharSequence CONTENT_DISPOSITION = builtInHeader("content-disposition");
    /**
     * {@code "content-md5"}
     */
    public static final CharSequence CONTENT_MD5 = builtInHeader("content-md5");
    /**
     * {@code "content-range"}
     */
    public static final CharSequence CONTENT_RANGE = builtInHeader("content-range");
    /**
     * {@code "content-security-policy"}
     */
    public static final CharSequence CONTENT_SECURITY_POLICY = builtInHeader("content-security-policy");
    /**
     * {@code "content-type"}
     */
    public static final CharSequence CONTENT_TYPE = builtInHeader("content-type");
    /**
     * {@code "cookie"}
     */
    public static final CharSequence COOKIE = builtInHeader("cookie");
    /**
     * {@code "date"}
     */
    public static final CharSequence DATE = builtInHeader("date");
    /**
     * {@code "etag"}
     */
    public static final CharSequence ETAG = builtInHeader("etag");
    /**
     * {@code "expect"}
     */
    public static final CharSequence EXPECT = builtInHeader("expect");
    /**
     * {@code "expires"}
     */
    public static final CharSequence EXPIRES = builtInHeader("expires");
    /**
     * {@code "forwarded"}
     */
    public static final CharSequence FORWARDED = builtInHeader("forwarded");
    /**
     * {@code "from"}
     */
    public static final CharSequence FROM = builtInHeader("from");
    /**
     * {@code "host"}
     */
    public static final CharSequence HOST = builtInHeader("host");
    /**
     * {@code "if-match"}
     */
    public static final CharSequence IF_MATCH = builtInHeader("if-match");
    /**
     * {@code "if-modified-since"}
     */
    public static final CharSequence IF_MODIFIED_SINCE = builtInHeader("if-modified-since");
    /**
     * {@code "if-none-match"}
     */
    public static final CharSequence IF_NONE_MATCH = builtInHeader("if-none-match");
    /**
     * {@code "if-range"}
     */
    public static final CharSequence IF_RANGE = builtInHeader("if-range");
    /**
     * {@code "if-unmodified-since"}
     */
    public static final CharSequence IF_UNMODIFIED_SINCE = builtInHeader("if-unmodified-since");
    /**
     * {@code "last-event-id"}
     */
    public static final CharSequence LAST_EVENT_ID = builtInHeader("last-event-id");
    /**
     * {@code "last-modified"}
     */
    public static final CharSequence LAST_MODIFIED = builtInHeader("last-modified");
    /**
     * {@code "link"}
     */
    public static final CharSequence LINK = builtInHeader("link");
    /**
     * {@code "location"}
     */
    public static final CharSequence LOCATION = builtInHeader("location");
    /**
     * {@code "max-forwards"}
     */
    public static final CharSequence MAX_FORWARDS = builtInHeader("max-forwards");
    /**
     * {@code "origin"}
     */
    public static final CharSequence ORIGIN = builtInHeader("origin");
    /**
     * {@code "pragma"}
     */
    public static final CharSequence PRAGMA = builtInHeader("pragma");
    /**
     * {@code "proxy-authenticate"}
     */
    public static final CharSequence PROXY_AUTHENTICATE = builtInHeader("proxy-authenticate");
    /**
     * {@code "proxy-authorization"}
     */
    public static final CharSequence PROXY_AUTHORIZATION = builtInHeader("proxy-authorization");
    /**
     * {@code "range"}
     */
    public static final CharSequence RANGE = builtInHeader("range");
    /**
     * {@code "referer"}
     */
    public static final CharSequence REFERER = builtInHeader("referer");
    /**
     * {@code "refresh"}
     */
    public static final CharSequence REFRESH = builtInHeader("refresh");
    /**
     * {@code "retry-after"}
     */
    public static final CharSequence RETRY_AFTER = builtInHeader("retry-after");
    /**
     * {@code "sec-websocket-key1"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY1 = builtInHeader("sec-websocket-key1");
    /**
     * {@code "sec-websocket-key2"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY2 = builtInHeader("sec-websocket-key2");
    /**
     * {@code "sec-websocket-location"}
     */
    public static final CharSequence SEC_WEBSOCKET_LOCATION = builtInHeader("sec-websocket-location");
    /**
     * {@code "sec-websocket-origin"}
     */
    public static final CharSequence SEC_WEBSOCKET_ORIGIN = builtInHeader("sec-websocket-origin");
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_PROTOCOL = builtInHeader("sec-websocket-protocol");
    /**
     * {@code "sec-websocket-version"}
     */
    public static final CharSequence SEC_WEBSOCKET_VERSION = builtInHeader("sec-websocket-version");
    /**
     * {@code "sec-websocket-key"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY = builtInHeader("sec-websocket-key");
    /**
     * {@code "sec-websocket-accept"}
     */
    public static final CharSequence SEC_WEBSOCKET_ACCEPT = builtInHeader("sec-websocket-accept");
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_EXTENSIONS = builtInHeader("sec-websocket-extensions");
    /**
     * {@code "server"}
     */
    public static final CharSequence SERVER = builtInHeader("server");
    /**
     * {@code "set-cookie"}
     */
    public static final CharSequence SET_COOKIE = builtInHeader("set-cookie");
    /**
     * {@code "set-cookie2"}
     */
    public static final CharSequence SET_COOKIE2 = builtInHeader("set-cookie2");

    /**
     * {@code "strict-transport-security"}
     */
    public static final CharSequence STRICT_TRANSPORT_SECURITY = builtInHeader("strict-transport-security");

    /**
     * {@code "te"}
     */
    public static final CharSequence TE = builtInHeader("te");
    /**
     * {@code "trailer"}
     */
    public static final CharSequence TRAILER = builtInHeader("trailer");
    /**
     * {@code "transfer-encoding"}
     */
    public static final CharSequence TRANSFER_ENCODING = builtInHeader("transfer-encoding");
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = builtInHeader("upgrade");
    /**
     * {@code "user-agent"}
     */
    public static final CharSequence USER_AGENT = builtInHeader("user-agent");
    /**
     * {@code "vary"}
     */
    public static final CharSequence VARY = builtInHeader("vary");
    /**
     * {@code "via"}
     */
    public static final CharSequence VIA = builtInHeader("via");
    /**
     * {@code "warning"}
     */
    public static final CharSequence WARNING = builtInHeader("warning");
    /**
     * {@code "websocket-location"}
     */
    public static final CharSequence WEBSOCKET_LOCATION = builtInHeader("websocket-location");
    /**
     * {@code "websocket-origin"}
     */
    public static final CharSequence WEBSOCKET_ORIGIN = builtInHeader("websocket-origin");
    /**
     * {@code "websocket-protocol"}
     */
    public static final CharSequence WEBSOCKET_PROTOCOL = builtInHeader("websocket-protocol");
    /**
     * {@code "www-authenticate"}
     */
    public static final CharSequence WWW_AUTHENTICATE = builtInHeader("www-authenticate");
    /**
     * {@code "content-type"}
     */
    public static final CharSequence X_CONTENT_TYPE_OPTIONS = builtInHeader("x-content-type-options");
    /**
     * {@code "x-forwarded-for"}
     */
    public static final CharSequence X_FORWARDED_FOR = builtInHeader("x-forwarded-for");
    /**
     * {@code "x-forwarded-host"}
     */
    public static final CharSequence X_FORWARDED_HOST = builtInHeader("x-forwarded-host");
    /**
     * {@code "x-forwarded-port"}
     */
    public static final CharSequence X_FORWARDED_PORT = builtInHeader("x-forwarded-port");
    /**
     * {@code "x-forwarded-proto"}
     */
    public static final CharSequence X_FORWARDED_PROTO = builtInHeader("x-forwarded-proto");

    /**
     * {@code "x-frame-options"}
     */
    public static final CharSequence X_FRAME_OPTIONS = builtInHeader("x-frame-options");


    static HeaderString PSEUDO_AUTHORITY = builtInHeader(":authority");
    static HeaderString PSEUDO_METHOD = builtInHeader(":method");
    static HeaderString PSEUDO_PATH = builtInHeader(":path");
    static HeaderString PSEUDO_SCHEME = builtInHeader(":scheme");
    static HeaderString PSEUDO_STATUS = builtInHeader(":status");

    private HeaderNames() {
    }




    static HeaderString findBuiltIn(CharSequence name) {
        CharSequence search = name instanceof HeaderString ? name :  name.toString().toLowerCase();
        return builtIn.get(search);
    }
}

