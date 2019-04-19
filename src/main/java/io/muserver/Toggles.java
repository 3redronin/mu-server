package io.muserver;

/**
 * Temporary features that might be removed without notice.
 */
public class Toggles {

    /**
     * An obsolete toggle that does nothing.
     * @deprecated This is now unused and fixed length is always enabled
     */
    public static boolean fixedLengthResponsesEnabled = false;

    static boolean http2 = true && !"1.8".equals(System.getProperty("java.specification.version"));

}
