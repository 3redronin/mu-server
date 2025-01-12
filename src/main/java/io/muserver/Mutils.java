package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Utility methods
 */
public class Mutils {

    /**
     * The new-line character for the current platform, e.g. <code>\n</code> in Linux or <code>\r\n</code> on Windows.
     */
    public static final String NEWLINE = String.format("%n");

    /**
     * @param value the value to encode
     * @return Returns the UTF-8 URL encoded value
     */
    public static String urlEncode(String value) {
        return URLEncoder.encode(value, UTF_8)
            .replace("+", "%20")
            .replace("%7E", "~") // as per https://www.ietf.org/rfc/rfc3986.html ~ doesn't need encoding
            ;
    }

    /**
     * @param value the value to decode
     * @return Returns the UTF-8 URL decoded value
     */
    public static String urlDecode(String value) {
        return URLDecoder.decode(value, UTF_8);
    }

    private Mutils() {
    }

    /**
     * Copies an input stream to another stream
     *
     * @param from       The source of the bytes
     * @param to         The destination of the bytes
     * @param bufferSize The size of the byte buffer to use as bytes are copied
     * @throws IOException Thrown if there is a problem reading from or writing to either stream
     */
    public static void copy(InputStream from, OutputStream to, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = from.read(buffer)) > -1) {
            if (read > 0) {
                to.write(buffer, 0, read);
            }
        }
    }

    /**
     * Reads the given input stream into a byte array and closes the input stream
     *
     * @param source     The source of the bytes
     * @param bufferSize The size of the byte buffer to use when copying streams
     * @return Returns a byte array
     * @throws IOException If there is an error reading from the stream
     */
    public static byte[] toByteArray(InputStream source, int bufferSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(source, baos, bufferSize);
        source.close();
        return baos.toByteArray();
    }

    /**
     * Checks for a null string or string with a length of 9
     *
     * @param val The value to check
     * @return True if the value is null or a zero-length string.
     */
    public static boolean nullOrEmpty(@Nullable String val) {
        return val == null || val.isEmpty();
    }

    /**
     * Checks that a string is not null and has a length greater than 0.
     *
     * @param val The value to check
     * @return True if the string is 1 or more characters.
     */
    public static boolean hasValue(@Nullable String val) {
        return !nullOrEmpty(val);
    }


    /**
     * Joins two strings with the given separator, unless the first string ends with the separator, or the second
     * string begins with it. For example, the output <code>one/two</code> would be returned from <code>join("one", "two", "/")</code>
     * or <code>join("one/", "/two", "/")</code> or <code>join("one/", "two", "/")</code> or
     * <code>join("one", "/two", "/")</code>
     *
     * @param one The prefix
     * @param sep The separator to put between the two strings, if it is not there already
     * @param two The suffix
     * @return The joined strings
     */
    public static String join(@Nullable String one, String sep, @Nullable String two) {
        one = one == null ? "" : one;
        two = two == null ? "" : two;
        boolean oneEnds = one.endsWith(sep);
        boolean twoStarts = two.startsWith(sep);
        if (oneEnds && twoStarts) {
            return one + two.substring(sep.length());
        } else if (oneEnds || twoStarts) {
            return one + two;
        } else {
            return one + sep + two;
        }
    }

    /**
     * Trims the given string from the given value
     *
     * @param value  The value to be trimmed
     * @param toTrim The string to trim
     * @return The value with any occurrences of toTrim removed from the start and end of the value
     */
    public static String trim(String value, String toTrim) {
        int len = toTrim.length();
        while (value.startsWith(toTrim)) {
            value = value.substring(len);
        }
        while (value.endsWith(toTrim)) {
            value = value.substring(0, value.length() - len);
        }
        return value;
    }

    /**
     * Throws an {@link IllegalArgumentException} if the given value is null
     *
     * @param name  The name of the variable to check
     * @param value The value to check
     */
    public static void notNull(String name, @Nullable Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
    }

    /**
     * Gets the canonical path of the given file, or if that throws an exception then gets the absolute path.
     *
     * @param file The file to check
     * @return The canonical or absolute path of the given file
     */
    public static String fullPath(File file) {
        notNull("file", file);
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Converts a date into a date string as used in HTTP headers (defined in RFC 7321), for example <code>Tue, 15 Nov 1994 08:12:31 GMT</code>
     *
     * @param date A date to format
     * @return The date as a formatted string
     * @throws IllegalArgumentException If the date is null
     */
    public static String toHttpDate(Date date) {
        notNull("date", date);
        return DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(date.getTime()));
    }

    /**
     * Converts a date string as used in HTTP headers (defined in RFC 7321) into a date object
     *
     * @param date A date formatted like <code>Tue, 15 Nov 1994 08:12:31 GMT</code>
     * @return A date representing the string
     * @throws IllegalArgumentException If the date is null
     * @throws DateTimeParseException If the date is not a valid HTTP date format
     */
    public static Date fromHttpDate(String date) throws DateTimeParseException {
        notNull("date", date);
        return new Date(DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .parse(date, Instant::from)
            .toEpochMilli());
    }

    /**
     * <p>Very basic HTML encoding, converting characters such as <code>&lt;</code> to <code>&lt;lt;</code></p>
     * <p>Important: HTML encoding is a complex topic, and different schemes are needed depending on whether the string
     * is destined for a tag name, attribute, the contents of a tag, CSS, or JavaScript etc. It is recommended that
     * a fully featured text encoding library is used rather than this method.</p>
     *
     * @param value A value
     * @return A value that can be safely included inside HTML tags.
     */
    public static String htmlEncode(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;")
            ;
    }

    /**
     * Returns the first non-null value from the given values (or null if all values are null)
     *
     * @param values An array of values
     * @param <T>    The type of the value
     * @return The first object in the list that is not null (or null, if all are null)
     */
    @SafeVarargs
    public static <T> @Nullable T coalesce(@Nullable T... values) {
        for (@Nullable T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static void closeSilently(@Nullable Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Converts a string to a ByteBuffer with UTF-8 encoding.
     * @param text Some text to convert
     * @return A ByteBuffer containing the text as UTF-8 encoded bytes
     * @throws IllegalArgumentException if text is null
     */
    public static ByteBuffer toByteBuffer(String text) {
        notNull("text", text);
        if (text.isEmpty()) {
            return EMPTY_BUFFER;
        }
        return ByteBuffer.wrap(text.getBytes(UTF_8));
    }

    /**
     * <p>Given a gets the raw path and (if present) querystring portion of a URI.</p>
     * <p>Note: paths and querystrings are not URL decoded.</p>
     * @param uri The URI to get the info from
     * @return A string such as <code>/path?query=something</code>
     */
    public static String pathAndQuery(URI uri) {
        String pathAndQuery = uri.getRawPath();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            pathAndQuery += "?" + rawQuery;
        }
        return pathAndQuery;
    }

    static boolean isTruthy(@Nullable String val) {
        if (val == null) { return false;}
        switch (val.toLowerCase()) {
            case "true":
            case "on":
            case "yes":
            case "1":
                return true;
            default:
                return false;
        }
    }

    static String getRelativeUrl(String requestLineUrl) throws HttpException {
        try {
            URI requestUri = new URI(requestLineUrl).normalize();
            if (requestUri.getScheme() == null && requestUri.getHost() != null) {
                throw HttpException.redirect(new URI(requestLineUrl.substring(1)).normalize());
            }

            String s = requestUri.getRawPath();
            if (Mutils.nullOrEmpty(s)) {
                s = "/";
            } else {
                // TODO: consider a redirect if the URL is changed? Handle other percent-encoded characters?
                s = s.replace("%7E", "~")
                    .replace("%5F", "_")
                    .replace("%2E", ".")
                    .replace("%2D", "-")
                ;
            }
            String q = requestUri.getRawQuery();
            if (q != null) {
                s += "?" + q;
            }
            return s;
        } catch (Exception e) {
            if (e instanceof HttpException) throw (HttpException) e;
            throw new HttpException(HttpStatus.BAD_REQUEST_400, "Invalid request URL");
        }
    }

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    /**
     * Ensures the given buffer has at least the amount of bytes requested.
     *
     * <p>The amount to read must be able to fit into the buffer, i.e. must be less than or equal to the buffer's capacity.</p>
     *
     * <p>If the buffer's remaining() value is enough to satisfy the request, nothing is changed. Otherwise, the input
     * stream will be read into the buffer. The buffer will be compacted if required.</p>
     *
     * @throws IllegalArgumentException if the buffer's capacity is less than minBytes
     * @throws IOException there is an error while reading
     * @throws EOFException the stream ends before min bytes are available
     */
    static void readAtLeast(ByteBuffer buffer, InputStream inputStream, int minBytes) throws IOException {
        if (minBytes > buffer.capacity()) throw new IllegalArgumentException("This buffer is not big enough");
        while (buffer.remaining() < minBytes) {
            if (buffer.capacity() - buffer.limit() < minBytes && buffer.position() > 0) {
                buffer.compact().flip();
            }
            int read = inputStream.read(buffer.array(), buffer.arrayOffset() + buffer.limit(), buffer.capacity() - buffer.limit());
            if (read == -1) {
                throw new EOFException();
            }
            buffer.limit(buffer.limit() + read);
        }
    }
}
