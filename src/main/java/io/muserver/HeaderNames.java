package io.muserver;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;

/**
 * Standard HTTP header names.
 * <p>
 * These are all defined as lowercase to support HTTP/2 requirements while also not
 * violating HTTP/1.x requirements.  New header names should always be lowercase.
 */
public final class HeaderNames {
    /**
     * {@code "accept"}
     */
    public static final CharSequence ACCEPT = HttpHeaderNames.ACCEPT;
    /**
     * {@code "accept-charset"}
     */
    public static final CharSequence ACCEPT_CHARSET = HttpHeaderNames.ACCEPT_CHARSET;
    /**
     * {@code "accept-encoding"}
     */
    public static final CharSequence ACCEPT_ENCODING = HttpHeaderNames.ACCEPT_ENCODING;
    /**
     * {@code "accept-language"}
     */
    public static final CharSequence ACCEPT_LANGUAGE = HttpHeaderNames.ACCEPT_LANGUAGE;
    /**
     * {@code "accept-ranges"}
     */
    public static final CharSequence ACCEPT_RANGES = HttpHeaderNames.ACCEPT_RANGES;
    /**
     * {@code "accept-patch"}
     */
    public static final CharSequence ACCEPT_PATCH = HttpHeaderNames.ACCEPT_PATCH;
    /**
     * {@code "access-control-allow-credentials"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_CREDENTIALS = HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS;
    /**
     * {@code "access-control-allow-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_HEADERS = HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS;
    /**
     * {@code "access-control-allow-methods"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_METHODS = HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS;
    /**
     * {@code "access-control-allow-origin"}
     */
    public static final CharSequence ACCESS_CONTROL_ALLOW_ORIGIN = HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN;
    /**
     * {@code "access-control-expose-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_EXPOSE_HEADERS = HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS;
    /**
     * {@code "access-control-max-age"}
     */
    public static final CharSequence ACCESS_CONTROL_MAX_AGE = HttpHeaderNames.ACCESS_CONTROL_MAX_AGE;
    /**
     * {@code "access-control-request-headers"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_HEADERS = HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS;
    /**
     * {@code "access-control-request-method"}
     */
    public static final CharSequence ACCESS_CONTROL_REQUEST_METHOD = HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD;
    /**
     * {@code "age"}
     */
    public static final CharSequence AGE = HttpHeaderNames.AGE;
    /**
     * {@code "allow"}
     */
    public static final CharSequence ALLOW = HttpHeaderNames.ALLOW;
    /**
     * {@code "authorization"}
     */
    public static final CharSequence AUTHORIZATION = HttpHeaderNames.AUTHORIZATION;
    /**
     * {@code "cache-control"}
     */
    public static final CharSequence CACHE_CONTROL = HttpHeaderNames.CACHE_CONTROL;
    /**
     * {@code "connection"}
     */
    public static final CharSequence CONNECTION = HttpHeaderNames.CONNECTION;
    /**
     * {@code "content-base"}
     */
    public static final CharSequence CONTENT_BASE = HttpHeaderNames.CONTENT_BASE;
    /**
     * {@code "content-encoding"}
     */
    public static final CharSequence CONTENT_ENCODING = HttpHeaderNames.CONTENT_ENCODING;
    /**
     * {@code "content-language"}
     */
    public static final CharSequence CONTENT_LANGUAGE = HttpHeaderNames.CONTENT_LANGUAGE;
    /**
     * {@code "content-length"}
     */
    public static final CharSequence CONTENT_LENGTH = HttpHeaderNames.CONTENT_LENGTH;
    /**
     * {@code "content-location"}
     */
    public static final CharSequence CONTENT_LOCATION = HttpHeaderNames.CONTENT_LOCATION;
    /**
     * {@code "content-transfer-encoding"}
     */
    public static final CharSequence CONTENT_TRANSFER_ENCODING = HttpHeaderNames.CONTENT_TRANSFER_ENCODING;
    /**
     * {@code "content-disposition"}
     */
    public static final CharSequence CONTENT_DISPOSITION = HttpHeaderNames.CONTENT_DISPOSITION;
    /**
     * {@code "content-md5"}
     */
    public static final CharSequence CONTENT_MD5 = HttpHeaderNames.CONTENT_MD5;
    /**
     * {@code "content-range"}
     */
    public static final CharSequence CONTENT_RANGE = HttpHeaderNames.CONTENT_RANGE;
    /**
     * {@code "content-security-policy"}
     */
    public static final CharSequence CONTENT_SECURITY_POLICY = HttpHeaderNames.CONTENT_SECURITY_POLICY;
    /**
     * {@code "content-type"}
     */
    public static final CharSequence CONTENT_TYPE = HttpHeaderNames.CONTENT_TYPE;
    /**
     * {@code "cookie"}
     */
    public static final CharSequence COOKIE = HttpHeaderNames.COOKIE;
    /**
     * {@code "date"}
     */
    public static final CharSequence DATE = HttpHeaderNames.DATE;
    /**
     * {@code "etag"}
     */
    public static final CharSequence ETAG = HttpHeaderNames.ETAG;
    /**
     * {@code "expect"}
     */
    public static final CharSequence EXPECT = HttpHeaderNames.EXPECT;
    /**
     * {@code "expires"}
     */
    public static final CharSequence EXPIRES = HttpHeaderNames.EXPIRES;
    /**
     * {@code "from"}
     */
    public static final CharSequence FROM = HttpHeaderNames.FROM;
    /**
     * {@code "host"}
     */
    public static final CharSequence HOST = HttpHeaderNames.HOST;
    /**
     * {@code "if-match"}
     */
    public static final CharSequence IF_MATCH = HttpHeaderNames.IF_MATCH;
    /**
     * {@code "if-modified-since"}
     */
    public static final CharSequence IF_MODIFIED_SINCE = HttpHeaderNames.IF_MODIFIED_SINCE;
    /**
     * {@code "if-none-match"}
     */
    public static final CharSequence IF_NONE_MATCH = HttpHeaderNames.IF_NONE_MATCH;
    /**
     * {@code "if-range"}
     */
    public static final CharSequence IF_RANGE = HttpHeaderNames.IF_RANGE;
    /**
     * {@code "if-unmodified-since"}
     */
    public static final CharSequence IF_UNMODIFIED_SINCE = HttpHeaderNames.IF_UNMODIFIED_SINCE;
    /**
     * {@code "last-event-id"}
     */
    public static final CharSequence LAST_EVENT_ID = AsciiString.cached("last-event-id");
    /**
     * {@code "last-modified"}
     */
    public static final CharSequence LAST_MODIFIED = HttpHeaderNames.LAST_MODIFIED;
    /**
     * {@code "link"}
     */
    public static final CharSequence LINK = AsciiString.cached("link");
    /**
     * {@code "location"}
     */
    public static final CharSequence LOCATION = HttpHeaderNames.LOCATION;
    /**
     * {@code "max-forwards"}
     */
    public static final CharSequence MAX_FORWARDS = HttpHeaderNames.MAX_FORWARDS;
    /**
     * {@code "origin"}
     */
    public static final CharSequence ORIGIN = HttpHeaderNames.ORIGIN;
    /**
     * {@code "pragma"}
     */
    public static final CharSequence PRAGMA = HttpHeaderNames.PRAGMA;
    /**
     * {@code "proxy-authenticate"}
     */
    public static final CharSequence PROXY_AUTHENTICATE = HttpHeaderNames.PROXY_AUTHENTICATE;
    /**
     * {@code "proxy-authorization"}
     */
    public static final CharSequence PROXY_AUTHORIZATION = HttpHeaderNames.PROXY_AUTHORIZATION;
    /**
     * {@code "range"}
     */
    public static final CharSequence RANGE = HttpHeaderNames.RANGE;
    /**
     * {@code "referer"}
     */
    public static final CharSequence REFERER = HttpHeaderNames.REFERER;
    /**
     * {@code "retry-after"}
     */
    public static final CharSequence RETRY_AFTER = HttpHeaderNames.RETRY_AFTER;
    /**
     * {@code "sec-websocket-key1"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY1 = HttpHeaderNames.SEC_WEBSOCKET_KEY1;
    /**
     * {@code "sec-websocket-key2"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY2 = HttpHeaderNames.SEC_WEBSOCKET_KEY2;
    /**
     * {@code "sec-websocket-location"}
     */
    public static final CharSequence SEC_WEBSOCKET_LOCATION = HttpHeaderNames.SEC_WEBSOCKET_LOCATION;
    /**
     * {@code "sec-websocket-origin"}
     */
    public static final CharSequence SEC_WEBSOCKET_ORIGIN = HttpHeaderNames.SEC_WEBSOCKET_ORIGIN;
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_PROTOCOL = HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL;
    /**
     * {@code "sec-websocket-version"}
     */
    public static final CharSequence SEC_WEBSOCKET_VERSION = HttpHeaderNames.SEC_WEBSOCKET_VERSION;
    /**
     * {@code "sec-websocket-key"}
     */
    public static final CharSequence SEC_WEBSOCKET_KEY = HttpHeaderNames.SEC_WEBSOCKET_KEY;
    /**
     * {@code "sec-websocket-accept"}
     */
    public static final CharSequence SEC_WEBSOCKET_ACCEPT = HttpHeaderNames.SEC_WEBSOCKET_ACCEPT;
    /**
     * {@code "sec-websocket-protocol"}
     */
    public static final CharSequence SEC_WEBSOCKET_EXTENSIONS = HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS;
    /**
     * {@code "server"}
     */
    public static final CharSequence SERVER = HttpHeaderNames.SERVER;
    /**
     * {@code "set-cookie"}
     */
    public static final CharSequence SET_COOKIE = HttpHeaderNames.SET_COOKIE;
    /**
     * {@code "set-cookie2"}
     */
    public static final CharSequence SET_COOKIE2 = HttpHeaderNames.SET_COOKIE2;

    /**
     * {@code "te"}
     */
    public static final CharSequence STRICT_TRANSPORT_SECURITY = AsciiString.cached("strict-transport-security");

    /**
     * {@code "te"}
     */
    public static final CharSequence TE = HttpHeaderNames.TE;
    /**
     * {@code "trailer"}
     */
    public static final CharSequence TRAILER = HttpHeaderNames.TRAILER;
    /**
     * {@code "transfer-encoding"}
     */
    public static final CharSequence TRANSFER_ENCODING = HttpHeaderNames.TRANSFER_ENCODING;
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = HttpHeaderNames.UPGRADE;
    /**
     * {@code "user-agent"}
     */
    public static final CharSequence USER_AGENT = HttpHeaderNames.USER_AGENT;
    /**
     * {@code "vary"}
     */
    public static final CharSequence VARY = HttpHeaderNames.VARY;
    /**
     * {@code "via"}
     */
    public static final CharSequence VIA = HttpHeaderNames.VIA;
    /**
     * {@code "warning"}
     */
    public static final CharSequence WARNING = HttpHeaderNames.WARNING;
    /**
     * {@code "websocket-location"}
     */
    public static final CharSequence WEBSOCKET_LOCATION = HttpHeaderNames.WEBSOCKET_LOCATION;
    /**
     * {@code "websocket-origin"}
     */
    public static final CharSequence WEBSOCKET_ORIGIN = HttpHeaderNames.WEBSOCKET_ORIGIN;
    /**
     * {@code "websocket-protocol"}
     */
    public static final CharSequence WEBSOCKET_PROTOCOL = HttpHeaderNames.WEBSOCKET_PROTOCOL;
    /**
     * {@code "www-authenticate"}
     */
    public static final CharSequence WWW_AUTHENTICATE = HttpHeaderNames.WWW_AUTHENTICATE;
    /**
     * {@code "content-type"}
     */
    public static final CharSequence X_CONTENT_TYPE_OPTIONS = AsciiString.cached("x-content-type-options");
    /**
     * {@code "x-forwarded-for"}
     */
    public static final CharSequence X_FORWARDED_FOR = AsciiString.cached("x-forwarded-for");
    /**
     * {@code "x-forwarded-host"}
     */
    public static final CharSequence X_FORWARDED_HOST = AsciiString.cached("x-forwarded-host");
    /**
     * {@code "x-forwarded-port"}
     */
    public static final CharSequence X_FORWARDED_PORT = AsciiString.cached("x-forwarded-port");
    /**
     * {@code "x-forwarded-proto"}
     */
    public static final CharSequence X_FORWARDED_PROTO = AsciiString.cached("x-forwarded-proto");

    /**
     * {@code "x-frame-options"}
     */
    public static final CharSequence X_FRAME_OPTIONS = HttpHeaderNames.X_FRAME_OPTIONS;



    private HeaderNames() {
    }
}

