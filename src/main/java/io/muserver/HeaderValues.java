package io.muserver;

import org.jspecify.annotations.NullMarked;

/**
 * Standard HTTP header values.
 */
@NullMarked
public final class HeaderValues {
    private static HeaderString headerValue(String attachment) {
        return HeaderString.valueOf(attachment, HeaderString.Type.VALUE);
    }

    /**
     * {@code "attachment"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence ATTACHMENT = headerValue("attachment");


    /**
     * {@code "base64"}
     */
    public static final CharSequence BASE64 = headerValue("base64");
    /**
     * {@code "binary"}
     */
    public static final CharSequence BINARY = headerValue("binary");
    /**
     * {@code "boundary"}
     */
    public static final CharSequence BOUNDARY = headerValue("boundary");
    /**
     * {@code "bytes"}
     */
    public static final CharSequence BYTES = headerValue("bytes");
    /**
     * {@code "charset"}
     */
    public static final CharSequence CHARSET = headerValue("charset");
    /**
     * {@code "chunked"}
     */
    public static final CharSequence CHUNKED = headerValue("chunked");
    /**
     * {@code "close"}
     */
    public static final CharSequence CLOSE = headerValue("close");
    /**
     * {@code "compress"}
     */
    public static final CharSequence COMPRESS = headerValue("compress");
    /**
     * {@code "100-continue"}
     */
    public static final CharSequence CONTINUE = headerValue("100-continue");
    /**
     * {@code "deflate"}
     */
    public static final CharSequence DEFLATE = headerValue("deflate");
    /**
     * {@code "x-deflate"}
     */
    public static final CharSequence X_DEFLATE = headerValue("x-deflate");
    /**
     * {@code "file"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILE = headerValue("file");
    /**
     * {@code "filename"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILENAME = headerValue("filename");
    /**
     * {@code "form-data"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FORM_DATA = headerValue("form-data");
    /**
     * {@code "gzip"}
     */
    public static final CharSequence GZIP = headerValue("gzip");
    /**
     * {@code "gzip,deflate"}
     */
    public static final CharSequence GZIP_DEFLATE = headerValue("gzip,deflate");
    /**
     * {@code "x-gzip"}
     */
    public static final CharSequence X_GZIP = headerValue("x-gzip");
    /**
     * {@code "identity"}
     */
    public static final CharSequence IDENTITY = headerValue("identity");
    /**
     * {@code "keep-alive"}
     */
    public static final CharSequence KEEP_ALIVE = headerValue("keep-alive");
    /**
     * {@code "max-age"}
     */
    public static final CharSequence MAX_AGE = headerValue("max-age");
    /**
     * {@code "max-stale"}
     */
    public static final CharSequence MAX_STALE = headerValue("max-stale");
    /**
     * {@code "min-fresh"}
     */
    public static final CharSequence MIN_FRESH = headerValue("min-fresh");
    /**
     * {@code "multipart/form-data"}
     */
    public static final CharSequence MULTIPART_FORM_DATA = headerValue("multipart/form-data");
    /**
     * {@code "multipart/mixed"}
     */
    public static final CharSequence MULTIPART_MIXED = headerValue("multipart/mixed");
    /**
     * {@code "must-revalidate"}
     */
    public static final CharSequence MUST_REVALIDATE = headerValue("must-revalidate");
    /**
     * {@code "name"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence NAME = headerValue("name");
    /**
     * {@code "no-cache"}
     */
    public static final CharSequence NO_CACHE = headerValue("no-cache");
    /**
     * {@code "no-store"}
     */
    public static final CharSequence NO_STORE = headerValue("no-store");
    /**
     * {@code "nosniff"}
     */
    public static final CharSequence NOSNIFF = headerValue("nosniff");
    /**
     * {@code "no-transform"}
     */
    public static final CharSequence NO_TRANSFORM = headerValue("no-transform");
    /**
     * {@code "none"}
     */
    public static final CharSequence NONE = headerValue("none");
    /**
     * {@code "0"}
     */
    public static final CharSequence ZERO = headerValue("0");
    /**
     * {@code "13"}
     */
    public static final CharSequence THIRTEEN = headerValue("13");
    /**
     * {@code "only-if-cached"}
     */
    public static final CharSequence ONLY_IF_CACHED = headerValue("only-if-cached");
    /**
     * {@code "private"}
     */
    public static final CharSequence PRIVATE = headerValue("private");
    /**
     * {@code "proxy-revalidate"}
     */
    public static final CharSequence PROXY_REVALIDATE = headerValue("proxy-revalidate");
    /**
     * {@code "public"}
     */
    public static final CharSequence PUBLIC = headerValue("public");
    /**
     * {@code "quoted-printable"}
     */
    public static final CharSequence QUOTED_PRINTABLE = headerValue("quoted-printable");
    /**
     * {@code "s-maxage"}
     */
    public static final CharSequence S_MAXAGE = headerValue("s-maxage");
    /**
     * {@code "trailers"}
     */
    public static final CharSequence TRAILERS = headerValue("trailers");
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = headerValue("upgrade");
    /**
     * {@code "websocket"}
     */
    public static final CharSequence WEBSOCKET = headerValue("websocket");

    private HeaderValues() { }
}
