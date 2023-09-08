package io.muserver;

import java.net.URI;

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

/**
 * Thrown when an HttpExchange can't start because the request is invalid
 */
class InvalidHttpRequestException extends Exception {
    final int code;
    final String codeTitle;
    InvalidHttpRequestException(int code, String clientMessage, String codeTitle) {
        super(clientMessage);
        this.code = code;
        this.codeTitle = codeTitle;
    }
}

class RedirectException extends Exception {
    final URI location;
    RedirectException(URI location) {
        this.location = location;
    }
}

/**
 * Thrown when an exchange gets a message it wasn't expected
 */
class UnexpectedMessageException extends RuntimeException {
    final Exchange exchange;
    final Object unexpectedMessage;
    UnexpectedMessageException(Exchange exchange, Object unexpectedMessage) {
        this.exchange = exchange;
        this.unexpectedMessage = unexpectedMessage;
    }
}

/**
 * Exchanges can throw these to connections when something goes wrong
 */
class MuExceptionFiredEvent {
    final Exchange exchange;
    final int streamId;
    final Throwable error;
    MuExceptionFiredEvent(Exchange exchange, int streamId, Throwable error) {
        this.exchange = exchange;
        this.streamId = streamId;
        this.error = error;
    }
}