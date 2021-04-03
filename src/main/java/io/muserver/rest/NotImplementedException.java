package io.muserver.rest;

/**
 * Thrown when a feature in MuServer that is not implemented is invoked
 */
class NotImplementedException extends RuntimeException {

    NotImplementedException(String message) {
        super(message);
    }

    static NotImplementedException notYet() {
        return new NotImplementedException("This is not yet implemented by MuServer, but will be in the future.");
    }
    static NotImplementedException willNot() {
        return new NotImplementedException("This will never be supported by MuServer");
    }
}
