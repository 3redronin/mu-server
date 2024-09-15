package io.muserver;

import java.io.InputStream;

/**
 * An input stream which always returns EOF
 * <p>Closing this stream has no effect.</p>
 */
public class EmptyInputStream extends InputStream {

    /**
     * The singleton instance
     */
    public static final InputStream INSTANCE = new EmptyInputStream();

    private EmptyInputStream() {
    }

    /**
     * Returns EOF
     * @return <code>-1</code>
     */
    public int read() {
        return -1;
    }
}
