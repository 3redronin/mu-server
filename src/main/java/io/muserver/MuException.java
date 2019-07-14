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
