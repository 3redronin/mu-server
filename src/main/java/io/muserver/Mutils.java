package io.muserver;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Objects;
import java.util.stream.Stream;

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
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new MuException("Error encoding " + value, e);
        }
    }

    /**
     * @param value the value to decode
     * @return Returns the UTF-8 URL decoded value
     */
    public static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new MuException("Error encoding " + value, e);
        }
    }

    private Mutils() {}

    /**
     * Copies an input stream to another stream
     * @param from The source of the bytes
     * @param to The destination of the bytes
     * @param bufferSize The size of the byte buffer to use as bytes are copied
     * @throws IOException Thrown if there is a problem reading from or writing to either stream
     */
    public static void copy(InputStream from, OutputStream to, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = from.read(buffer)) > -1) {
            to.write(buffer, 0, read);
        }
    }

    /**
     * Reads the given input stream into a byte array and closes the input stream
     * @param source The source of the bytes
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
     * @param val The value to check
     * @return True if the value is null or a zero-length string.
     */
    public static boolean nullOrEmpty(String val) {
        return val == null || val.length() == 0;
    }

    /**
     * Checks that a string is not null and has a length greater than 0.
     * @param val The value to check
     * @return True if the string is 1 or more characters.
     */
    public static boolean hasValue(String val) {
        return !nullOrEmpty(val);
    }


    /**
     * Joins two strings with the given separator, unless the first string ends with the separator, or the second
     * string begins with it. For example, the output <code>one/two</code> would be returned from <code>join("one", "two", "/")</code>
     * or <code>join("one/", "/two", "/")</code> or <code>join("one/", "two", "/")</code> or
     * <code>join("one", "/two", "/")</code>
     * @param one The prefix
     * @param sep The separator to put between the two strings, if it is not there already
     * @param two The suffix
     * @return The joined strings
     */
    public static String join(String one, String sep, String two) {
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
     * @param value The value to be trimmed
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
     * @param name The name of the variable to check
     * @param value The value to check
     */
    public static void notNull(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
    }

    /**
     * Gets the canonical path of the given file, or if that throws an exception then gets the absolute path.
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
     * <p>Very basic HTML encoding, converting characters such as <code>&lt;</code> to <code>&lt;lt;</code></p>
     * <p>Important: HTML encoding is a complex topic, and different schemes are needed depending on whether the string
     * is destined for a tag name, attribute, the contents of a tag, CSS, or JavaScript etc. It is recommended that
     * a fully featured text encoding library is used rather than this method.</p>
     * @param value A value
     * @return A value that can be safely included inside HTML tags.
     */
    public static String htmlEncode(String value) {
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
     * @param values An array of values
     * @param <T> The type of the value
     * @return The first object in the list that is not null (or null, if all are null)
     */
    public static <T> T coalesce(T... values) {
        return Stream.of(values).filter(Objects::nonNull).findFirst().orElse(null);
    }
}
