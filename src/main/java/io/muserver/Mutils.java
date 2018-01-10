package io.muserver;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;

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
            return URLEncoder.encode(value, "UTF-8");
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

    public static boolean nullOrEmpty(String val) {
        return val == null || val.length() == 0;
    }
    public static boolean hasValue(String val) {
        return !nullOrEmpty(val);
    }

}
