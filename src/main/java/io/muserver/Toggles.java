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

    /**
     * Enables experimental HTTP2 support.
     */
    public static boolean http2 = false;

}
