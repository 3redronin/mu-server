package io.muserver;

class InvalidRequestException extends Exception {
    final HttpStatusCode status;
    final String clientMessage;
    final String privateDetails;

    InvalidRequestException(HttpStatusCode status, String clientMessage, String privateDetails) {
        super(status + " " + clientMessage + " - " + privateDetails);
        this.status = status;
        this.clientMessage = clientMessage;
        this.privateDetails = privateDetails;
    }
}
