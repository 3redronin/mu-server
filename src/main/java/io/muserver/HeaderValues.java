package io.muserver;

/**
 * Standard HTTP header values.
 */
public final class HeaderValues {
    /**
     * {@code "attachment"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence ATTACHMENT = HeaderString.valueOf("attachment");
    /**
     * {@code "base64"}
     */
    public static final CharSequence BASE64 = HeaderString.valueOf("base64");
    /**
     * {@code "binary"}
     */
    public static final CharSequence BINARY = HeaderString.valueOf("binary");
    /**
     * {@code "boundary"}
     */
    public static final CharSequence BOUNDARY = HeaderString.valueOf("boundary");
    /**
     * {@code "bytes"}
     */
    public static final CharSequence BYTES = HeaderString.valueOf("bytes");
    /**
     * {@code "charset"}
     */
    public static final CharSequence CHARSET = HeaderString.valueOf("charset");
    /**
     * {@code "chunked"}
     */
    public static final CharSequence CHUNKED = HeaderString.valueOf("chunked");
    /**
     * {@code "close"}
     */
    public static final CharSequence CLOSE = HeaderString.valueOf("close");
    /**
     * {@code "compress"}
     */
    public static final CharSequence COMPRESS = HeaderString.valueOf("compress");
    /**
     * {@code "100-continue"}
     */
    public static final CharSequence CONTINUE = HeaderString.valueOf("100-continue");
    /**
     * {@code "deflate"}
     */
    public static final CharSequence DEFLATE = HeaderString.valueOf("deflate");
    /**
     * {@code "x-deflate"}
     */
    public static final CharSequence X_DEFLATE = HeaderString.valueOf("x-deflate");
    /**
     * {@code "file"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILE = HeaderString.valueOf("file");
    /**
     * {@code "filename"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FILENAME = HeaderString.valueOf("filename");
    /**
     * {@code "form-data"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence FORM_DATA = HeaderString.valueOf("form-data");
    /**
     * {@code "gzip"}
     */
    public static final CharSequence GZIP = HeaderString.valueOf("gzip");
    /**
     * {@code "gzip,deflate"}
     */
    public static final CharSequence GZIP_DEFLATE = HeaderString.valueOf("gzip,deflate");
    /**
     * {@code "x-gzip"}
     */
    public static final CharSequence X_GZIP = HeaderString.valueOf("x-gzip");
    /**
     * {@code "identity"}
     */
    public static final CharSequence IDENTITY = HeaderString.valueOf("identity");
    /**
     * {@code "keep-alive"}
     */
    public static final CharSequence KEEP_ALIVE = HeaderString.valueOf("keep-alive");
    /**
     * {@code "max-age"}
     */
    public static final CharSequence MAX_AGE = HeaderString.valueOf("max-age");
    /**
     * {@code "max-stale"}
     */
    public static final CharSequence MAX_STALE = HeaderString.valueOf("max-stale");
    /**
     * {@code "min-fresh"}
     */
    public static final CharSequence MIN_FRESH = HeaderString.valueOf("min-fresh");
    /**
     * {@code "multipart/form-data"}
     */
    public static final CharSequence MULTIPART_FORM_DATA = HeaderString.valueOf("multipart/form-data");
    /**
     * {@code "multipart/mixed"}
     */
    public static final CharSequence MULTIPART_MIXED = HeaderString.valueOf("multipart/mixed");
    /**
     * {@code "must-revalidate"}
     */
    public static final CharSequence MUST_REVALIDATE = HeaderString.valueOf("must-revalidate");
    /**
     * {@code "name"}
     * See {@link HeaderNames#CONTENT_DISPOSITION}
     */
    public static final CharSequence NAME = HeaderString.valueOf("name");
    /**
     * {@code "no-cache"}
     */
    public static final CharSequence NO_CACHE = HeaderString.valueOf("no-cache");
    /**
     * {@code "no-store"}
     */
    public static final CharSequence NO_STORE = HeaderString.valueOf("no-store");
    /**
     * {@code "nosniff"}
     */
    public static final CharSequence NOSNIFF = HeaderString.valueOf("nosniff");
    /**
     * {@code "no-transform"}
     */
    public static final CharSequence NO_TRANSFORM = HeaderString.valueOf("no-transform");
    /**
     * {@code "none"}
     */
    public static final CharSequence NONE = HeaderString.valueOf("none");
    /**
     * {@code "0"}
     */
    public static final CharSequence ZERO = HeaderString.valueOf("0");
    /**
     * {@code "13"}
     */
    public static final CharSequence THIRTEEN = HeaderString.valueOf("13");
    /**
     * {@code "only-if-cached"}
     */
    public static final CharSequence ONLY_IF_CACHED = HeaderString.valueOf("only-if-cached");
    /**
     * {@code "private"}
     */
    public static final CharSequence PRIVATE = HeaderString.valueOf("private");
    /**
     * {@code "proxy-revalidate"}
     */
    public static final CharSequence PROXY_REVALIDATE = HeaderString.valueOf("proxy-revalidate");
    /**
     * {@code "public"}
     */
    public static final CharSequence PUBLIC = HeaderString.valueOf("public");
    /**
     * {@code "quoted-printable"}
     */
    public static final CharSequence QUOTED_PRINTABLE = HeaderString.valueOf("quoted-printable");
    /**
     * {@code "s-maxage"}
     */
    public static final CharSequence S_MAXAGE = HeaderString.valueOf("s-maxage");
    /**
     * {@code "trailers"}
     */
    public static final CharSequence TRAILERS = HeaderString.valueOf("trailers");
    /**
     * {@code "upgrade"}
     */
    public static final CharSequence UPGRADE = HeaderString.valueOf("upgrade");
    /**
     * {@code "websocket"}
     */
    public static final CharSequence WEBSOCKET = HeaderString.valueOf("websocket");

    private HeaderValues() { }
}
