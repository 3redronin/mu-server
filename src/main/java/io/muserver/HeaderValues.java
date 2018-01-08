package io.muserver;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

/**
 * Standard HTTP header values.
 */
public final class HeaderValues {
    /**
     * {@code "attachment"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence ATTACHMENT = HttpHeaderValues.ATTACHMENT;
    /**
     * {@code "base64"}
     */
    public static final CharSequence BASE64 = HttpHeaderValues.BASE64;
    /**
     * {@code "binary"}
     */
    public static final CharSequence BINARY = HttpHeaderValues.BINARY;
    /**
     * {@code "boundary"}
     */
    public static final CharSequence BOUNDARY = HttpHeaderValues.BOUNDARY;
    /**
     * {@code "bytes"}
     */
    public static final CharSequence BYTES = HttpHeaderValues.BYTES;
    /**
     * {@code "charset"}
     */
    public static final CharSequence CHARSET = HttpHeaderValues.CHARSET;
    /**
     * {@code "chunked"}
     */
    public static final CharSequence CHUNKED = HttpHeaderValues.CHUNKED;
    /**
     * {@code "close"}
     */
    public static final CharSequence CLOSE = HttpHeaderValues.CLOSE;
    /**
     * {@code "compress"}
     */
    public static final CharSequence COMPRESS = HttpHeaderValues.COMPRESS;
    /**
     * {@code "100-continue"}
     */
    public static final CharSequence CONTINUE = HttpHeaderValues.CONTINUE;
    /**
     * {@code "deflate"}
     */
    public static final CharSequence DEFLATE = HttpHeaderValues.DEFLATE;
    /**
     * {@code "x-deflate"}
     */
    public static final CharSequence X_DEFLATE = HttpHeaderValues.X_DEFLATE;
    /**
     * {@code "file"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILE = HttpHeaderValues.FILE;
    /**
     * {@code "filename"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILENAME = HttpHeaderValues.FILENAME;
    /**
     * {@code "form-data"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FORM_DATA = HttpHeaderValues.FORM_DATA;
    /**
     * {@code "gzip"}
     */
    public static final CharSequence GZIP = HttpHeaderValues.GZIP;
    /**
     * {@code "gzip,deflate"}
     */
    public static final CharSequence GZIP_DEFLATE = HttpHeaderValues.GZIP_DEFLATE;
    /**
     * {@code "x-gzip"}
     */
    public static final CharSequence X_GZIP = HttpHeaderValues.X_GZIP;
    /**
     * {@code "identity"}
     */
    public static final CharSequence IDENTITY = HttpHeaderValues.IDENTITY;
    /**
     * {@code "keep-alive"}
     */
    public static final CharSequence KEEP_ALIVE = HttpHeaderValues.KEEP_ALIVE;
    /**
     * {@code "max-age"}
     */
    public static final CharSequence MAX_AGE = HttpHeaderValues.MAX_AGE;
    /**
     * {@code "max-stale"}
     */
    public static final CharSequence MAX_STALE = HttpHeaderValues.MAX_STALE;
    /**
     * {@code "min-fresh"}
     */
    public static final CharSequence MIN_FRESH = HttpHeaderValues.MIN_FRESH;
    /**
     * {@code "multipart/form-data"}
     */
    public static final CharSequence MULTIPART_FORM_DATA = HttpHeaderValues.MULTIPART_FORM_DATA;
    /**
     * {@code "multipart/mixed"}
     */
    public static final CharSequence MULTIPART_MIXED = HttpHeaderValues.MULTIPART_MIXED;
    /**
     * {@code "must-revalidate"}
     */
    public static final CharSequence MUST_REVALIDATE = HttpHeaderValues.MUST_REVALIDATE;
    /**
     * {@code "name"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence NAME = HttpHeaderValues.NAME;
    /**
     * {@code "no-cache"}
     */
    public static final CharSequence NO_CACHE = HttpHeaderValues.NO_CACHE;
    /**
     * {@code "no-store"}
     */
    public static final CharSequence NO_STORE = HttpHeaderValues.NO_STORE;
    /**
     * {@code "nosniff"}
     */
    public static final CharSequence NOSNIFF = AsciiString.cached("nosniff");
    /**
     * {@code "no-transform"}
     */
    public static final CharSequence NO_TRANSFORM = HttpHeaderValues.NO_TRANSFORM;
    /**
     * {@code "none"}
     */
    public static final CharSequence NONE = HttpHeaderValues.NONE;
    /**
     * {@code "0"}
     */
    public static final CharSequence ZERO = HttpHeaderValues.ZERO;
    /**
     * {@code "only-if-cached"}
     */
    public static final CharSequence ONLY_IF_CACHED = HttpHeaderValues.ONLY_IF_CACHED;
    /**
     * {@code "private"}
     */
    public static final CharSequence PRIVATE = HttpHeaderValues.PRIVATE;
    /**
     * {@code "proxy-revalidate"}
     */
    public static final CharSequence PROXY_REVALIDATE = HttpHeaderValues.PROXY_REVALIDATE;
    /**
     * {@code "public"}
     */
    public static final CharSequence PUBLIC = HttpHeaderValues.PUBLIC;
    /**
     * {@code "quoted-printable"}
     */
    public static final CharSequence QUOTED_PRINTABLE = HttpHeaderValues.QUOTED_PRINTABLE;
    /**
     * {@code "s-maxage"}
     */
    public static final CharSequence S_MAXAGE = HttpHeaderValues.S_MAXAGE;
    /**
     * {@code "trailers"}
     */
    public static final CharSequence TRAILERS = HttpHeaderValues.TRAILERS;
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = HttpHeaderValues.UPGRADE;
    /**
     * {@code "websocket"}
     */
    public static final CharSequence WEBSOCKET = HttpHeaderValues.WEBSOCKET;

    private HeaderValues() { }
}
