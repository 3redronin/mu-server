package ronin.muserver;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

public class ContentTypes {
    /**
     * {@code "application/javascript"}
     */
    public static final CharSequence APPLICATION_JAVASCRIPT = AsciiString.cached("application/javascript");
    /**
     * {@code "application/json"}
     */
    public static final CharSequence APPLICATION_JSON = HttpHeaderValues.APPLICATION_JSON;
    /**
     * {@code "application/x-www-form-urlencoded"}
     */
    public static final CharSequence APPLICATION_X_WWW_FORM_URLENCODED = HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED;
    /**
     * {@code "application/octet-stream"}
     */
    public static final CharSequence APPLICATION_OCTET_STREAM = HttpHeaderValues.APPLICATION_OCTET_STREAM;
    /**
     * {@code "image/jpeg"}
     */
    public static final CharSequence IMAGE_JPEG = AsciiString.cached("image/jpeg");

    /**
     * {@code "text/css"}
     */
    public static final CharSequence TEXT_CSS = AsciiString.cached("text/css");
    /**
     * {@code "text/plain"}
     */
    public static final CharSequence TEXT_PLAIN = HttpHeaderValues.TEXT_PLAIN;
    /**
     * {@code "text/html"}
     */
    public static final CharSequence TEXT_HTML = AsciiString.cached("text/html");
}
