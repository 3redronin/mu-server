package io.muserver;

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
	public MuException(String message, Throwable cause) {
		super(message, cause);
	}
}

class InvalidHttpRequestException extends Exception {
    final int code;
    InvalidHttpRequestException(int code, String clientMessage) {
        super(clientMessage);
        this.code = code;
    }
}

class UnexpectedMessageException extends RuntimeException {
    final Exchange exchange;
    final Object unexpectedMessage;
    UnexpectedMessageException(Exchange exchange, Object unexpectedMessage) {
        this.exchange = exchange;
        this.unexpectedMessage = unexpectedMessage;
    }
}