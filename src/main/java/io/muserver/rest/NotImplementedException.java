package io.muserver.rest;

class NotImplementedException extends RuntimeException {

    public NotImplementedException() {
        super();
    }

    public NotImplementedException(String message) {
        super(message);
    }

    public static NotImplementedException notYet() {
        return new NotImplementedException("This is not yet implemented by MuServer, but will be in the future.");
    }
    public static NotImplementedException willNot() {
        return new NotImplementedException("This will never be supported by MuServer");
    }
}
