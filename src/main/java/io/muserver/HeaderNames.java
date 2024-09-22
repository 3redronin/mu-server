package io.muserver;


/**
 * <p>Standard HTTP header names.</p>
 * <p>These are all defined as lowercase to support HTTP/2 requirements while also not
 * violating HTTP/1.x requirements.  New header names should always be lowercase.</p>
 */
public final class HeaderNames {
    /**
     * {@code "accept"}
     */
    public static final CharSequence ACCEPT = HeaderString.valueOf("accept");
    /**
     * {@code "accept-charset"}
     */
    public static final CharSequence ACCEPT_CHARSET = HeaderString.valueOf("accept-charset");
    /**
     * {@code "accept-encoding"}
     */
    public static final CharSequence ACCEPT_ENCODING = HeaderString.valueOf("accept-encoding");
    /**
     * {@code "accept-language"}
     */
    public static final CharSequence ACCEPT_LANGUAGE = HeaderString.valueOf("accept-language");
    /**
     * {@code "accept-ranges"}
     */
    public static final CharSequence ACCEPT_RANGES = HeaderString.valueOf("accept-ranges");
    /**
     * {@code "accept-patch"}
     */
    public static final CharSequence ACCEPT_PATCH = HeaderString.valueOf("accept-patch");
    /**
     * {@code "access-control-allow-credentials"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_CREDENTIALS = HeaderString.valueOf("access-control-allow-credentials");
    /**
     * {@code "access-control-allow-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_HEADERS = HeaderString.valueOf("access-control-allow-headers");
    /**
     * {@code "access-control-allow-methods"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_METHODS = HeaderString.valueOf("access-control-allow-methods");
    /**
     * {@code "access-control-allow-origin"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_ORIGIN = HeaderString.valueOf("access-control-allow-origin");
    /**
     * {@code "access-control-expose-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_EXPOSE_HEADERS = HeaderString.valueOf("access-control-expose-headers");
    /**
     * {@code "access-control-max-age"}
     */
    public static final CharSequence ACCESS_CONTROL_MAX_AGE = HeaderString.valueOf("access-control-max-age");
    /**
     * {@code "access-control-request-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_HEADERS = HeaderString.valueOf("access-control-request-headers");
    /**
     * {@code "access-control-request-method"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_METHOD = HeaderString.valueOf("access-control-request-method");
    /**
     * {@code "age"}
     */
    public static final CharSequence AGE = HeaderString.valueOf("age");
    /**
     * {@code "allow"}
     */
    public static final CharSequence ALLOW = HeaderString.valueOf("allow");
    /**
     * {@code "authorization"}
     */
    public static final CharSequence AUTHORIZATION = HeaderString.valueOf("authorization");
    /**
     * {@code "cache-control"}
     */
    public static final CharSequence CACHE_CONTROL = HeaderString.valueOf("cache-control");
    /**
     * {@code "connection"}
     */
    public static final CharSequence CONNECTION = HeaderString.valueOf("connection");
    /**
     * {@code "content-base"}
     */
    public static final CharSequence CONTENT_BASE = HeaderString.valueOf("content-base");
    /**
     * {@code "content-encoding"}
     */
    public static final CharSequence CONTENT_ENCODING = HeaderString.valueOf("content-encoding");
    /**
     * {@code "content-language"}
     */
    public static final CharSequence CONTENT_LANGUAGE = HeaderString.valueOf("content-language");
    /**
     * {@code "content-length"}
     */
    public static final CharSequence CONTENT_LENGTH = HeaderString.valueOf("content-length");
    /**
     * {@code "content-location"}
     */
    public static final CharSequence CONTENT_LOCATION = HeaderString.valueOf("content-location");
    /**
     * {@code "content-transfer-encoding"}
     */
    public static final CharSequence CONTENT_TRANSFER_ENCODING = HeaderString.valueOf("content-transfer-encoding");
    /**
     * {@code "content-disposition"}
     */
    public static final CharSequence CONTENT_DISPOSITION = HeaderString.valueOf("content-disposition");
    /**
     * {@code "content-md5"}
     */
    public static final CharSequence CONTENT_MD5 = HeaderString.valueOf("content-md5");
    /**
     * {@code "content-range"}
     */
    public static final CharSequence CONTENT_RANGE = HeaderString.valueOf("content-range");
    /**
     * {@code "content-security-policy"}
     */
    public static final CharSequence CONTENT_SECURITY_POLICY = HeaderString.valueOf("content-security-policy");
    /**
     * {@code "content-type"}
     */
    public static final CharSequence CONTENT_TYPE = HeaderString.valueOf("content-type");
    /**
     * {@code "cookie"}
     */
    public static final CharSequence COOKIE = HeaderString.valueOf("cookie");
    /**
     * {@code "date"}
     */
    public static final CharSequence DATE = HeaderString.valueOf("date");
    /**
     * {@code "etag"}
     */
    public static final CharSequence ETAG = HeaderString.valueOf("etag");
    /**
     * {@code "expect"}
     */
    public static final CharSequence EXPECT = HeaderString.valueOf("expect");
    /**
     * {@code "expires"}
     */
    public static final CharSequence EXPIRES = HeaderString.valueOf("expires");
    /**
     * {@code "forwarded"}
     */
    public static final CharSequence FORWARDED = HeaderString.valueOf("forwarded");
    /**
     * {@code "from"}
     */
    public static final CharSequence FROM = HeaderString.valueOf("from");
    /**
     * {@code "host"}
     */
    public static final CharSequence HOST = HeaderString.valueOf("host");
    /**
     * {@code "if-match"}
     */
    public static final CharSequence IF_MATCH = HeaderString.valueOf("if-match");
    /**
     * {@code "if-modified-since"}
     */
    public static final CharSequence IF_MODIFIED_SINCE = HeaderString.valueOf("if-modified-since");
    /**
     * {@code "if-none-match"}
     */
    public static final CharSequence IF_NONE_MATCH = HeaderString.valueOf("if-none-match");
    /**
     * {@code "if-range"}
     */
    public static final CharSequence IF_RANGE = HeaderString.valueOf("if-range");
    /**
     * {@code "if-unmodified-since"}
     */
    public static final CharSequence IF_UNMODIFIED_SINCE = HeaderString.valueOf("if-unmodified-since");
    /**
     * {@code "last-event-id"}
     */
    public static final CharSequence LAST_EVENT_ID = HeaderString.valueOf("last-event-id");
    /**
     * {@code "last-modified"}
     */
    public static final CharSequence LAST_MODIFIED = HeaderString.valueOf("last-modified");
    /**
     * {@code "link"}
     */
    public static final CharSequence LINK = HeaderString.valueOf("link");
    /**
     * {@code "location"}
     */
    public static final CharSequence LOCATION = HeaderString.valueOf("location");
    /**
     * {@code "max-forwards"}
     */
    public static final CharSequence MAX_FORWARDS = HeaderString.valueOf("max-forwards");
    /**
     * {@code "origin"}
     */
    public static final CharSequence ORIGIN = HeaderString.valueOf("origin");
    /**
     * {@code "pragma"}
     */
    public static final CharSequence PRAGMA = HeaderString.valueOf("pragma");
    /**
     * {@code "proxy-authenticate"}
     */
    public static final CharSequence PROXY_AUTHENTICATE = HeaderString.valueOf("proxy-authenticate");
    /**
     * {@code "proxy-authorization"}
     */
    public static final CharSequence PROXY_AUTHORIZATION = HeaderString.valueOf("proxy-authorization");
    /**
     * {@code "range"}
     */
    public static final CharSequence RANGE = HeaderString.valueOf("range");
    /**
     * {@code "referer"}
     */
    public static final CharSequence REFERER = HeaderString.valueOf("referer");
    /**
     * {@code "retry-after"}
     */
    public static final CharSequence RETRY_AFTER = HeaderString.valueOf("retry-after");
    /**
     * {@code "sec-websocket-key1"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY1 = HeaderString.valueOf("sec-websocket-key1");
    /**
     * {@code "sec-websocket-key2"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY2 = HeaderString.valueOf("sec-websocket-key2");
    /**
     * {@code "sec-websocket-location"}
     */
    public static final CharSequence SEC_WEBSOCKET_LOCATION = HeaderString.valueOf("sec-websocket-location");
    /**
     * {@code "sec-websocket-origin"}
     */
    public static final CharSequence SEC_WEBSOCKET_ORIGIN = HeaderString.valueOf("sec-websocket-origin");
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_PROTOCOL = HeaderString.valueOf("sec-websocket-protocol");
    /**
     * {@code "sec-websocket-version"}
     */
    public static final CharSequence SEC_WEBSOCKET_VERSION = HeaderString.valueOf("sec-websocket-version");
    /**
     * {@code "sec-websocket-key"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY = HeaderString.valueOf("sec-websocket-key");
    /**
     * {@code "sec-websocket-accept"}
     */
    public static final CharSequence SEC_WEBSOCKET_ACCEPT = HeaderString.valueOf("sec-websocket-accept");
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_EXTENSIONS = HeaderString.valueOf("sec-websocket-extensions");
    /**
     * {@code "server"}
     */
    public static final CharSequence SERVER = HeaderString.valueOf("server");
    /**
     * {@code "set-cookie"}
     */
    public static final CharSequence SET_COOKIE = HeaderString.valueOf("set-cookie");
    /**
     * {@code "set-cookie2"}
     */
    public static final CharSequence SET_COOKIE2 = HeaderString.valueOf("set-cookie2");

    /**
     * {@code "te"}
     */
    public static final CharSequence STRICT_TRANSPORT_SECURITY = HeaderString.valueOf("strict-transport-security");

    /**
     * {@code "te"}
     */
    public static final CharSequence TE = HeaderString.valueOf("te");
    /**
     * {@code "trailer"}
     */
    public static final CharSequence TRAILER = HeaderString.valueOf("trailer");
    /**
     * {@code "transfer-encoding"}
     */
    public static final CharSequence TRANSFER_ENCODING = HeaderString.valueOf("transfer-encoding");
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = HeaderString.valueOf("upgrade");
    /**
     * {@code "user-agent"}
     */
    public static final CharSequence USER_AGENT = HeaderString.valueOf("user-agent");
    /**
     * {@code "vary"}
     */
    public static final CharSequence VARY = HeaderString.valueOf("vary");
    /**
     * {@code "via"}
     */
    public static final CharSequence VIA = HeaderString.valueOf("via");
    /**
     * {@code "warning"}
     */
    public static final CharSequence WARNING = HeaderString.valueOf("warning");
    /**
     * {@code "websocket-location"}
     */
    public static final CharSequence WEBSOCKET_LOCATION = HeaderString.valueOf("websocket-location");
    /**
     * {@code "websocket-origin"}
     */
    public static final CharSequence WEBSOCKET_ORIGIN = HeaderString.valueOf("websocket-origin");
    /**
     * {@code "websocket-protocol"}
     */
    public static final CharSequence WEBSOCKET_PROTOCOL = HeaderString.valueOf("websocket-protocol");
    /**
     * {@code "www-authenticate"}
     */
    public static final CharSequence WWW_AUTHENTICATE = HeaderString.valueOf("www-authenticate");
    /**
     * {@code "content-type"}
     */
    public static final CharSequence X_CONTENT_TYPE_OPTIONS = HeaderString.valueOf("x-content-type-options");
    /**
     * {@code "x-forwarded-for"}
     */
    public static final CharSequence X_FORWARDED_FOR = HeaderString.valueOf("x-forwarded-for");
    /**
     * {@code "x-forwarded-host"}
     */
    public static final CharSequence X_FORWARDED_HOST = HeaderString.valueOf("x-forwarded-host");
    /**
     * {@code "x-forwarded-port"}
     */
    public static final CharSequence X_FORWARDED_PORT = HeaderString.valueOf("x-forwarded-port");
    /**
     * {@code "x-forwarded-proto"}
     */
    public static final CharSequence X_FORWARDED_PROTO = HeaderString.valueOf("x-forwarded-proto");

    /**
     * {@code "x-frame-options"}
     */
    public static final CharSequence X_FRAME_OPTIONS = HeaderString.valueOf("x-frame-options");


    private HeaderNames() {
    }
}

