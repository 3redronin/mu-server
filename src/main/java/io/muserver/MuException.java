package io.muserver;

import org.jspecify.annotations.Nullable;

/**
 * A generic exception raised by mu-server
 */
public class MuException extends RuntimeException {

    /**
     * Creates a new exception
     * @param message The exception message
     */
	public MuException(String message) {
		super(message);
	}

    /**
     * Creates a new exception
     * @param message The exception message
     * @param cause The cause of the exception
     */
	public MuException(String message, @Nullable Throwable cause) {
		super(message, cause);
	}
}
