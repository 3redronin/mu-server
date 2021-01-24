package io.muserver;

public class MuException extends RuntimeException {

    public MuException() {
        super();
    }

	public MuException(String message) {
		super(message);
	}

	public MuException(String message, Throwable cause) {
		super(message, cause);
	}

	public MuException(Throwable cause) {
		super(cause);
	}
}

class InvalidHttpRequestException extends Exception {
    final int code;
    InvalidHttpRequestException(int code, String clientMessage) {
        super(clientMessage);
        this.code = code;
    }
}

class UnexpectedMessageException extends Exception {
    final Exchange exchange;
    final Object unexpectedMessage;
    UnexpectedMessageException(Exchange exchange, Object unexpectedMessage) {
        this.exchange = exchange;
        this.unexpectedMessage = unexpectedMessage;
    }
}