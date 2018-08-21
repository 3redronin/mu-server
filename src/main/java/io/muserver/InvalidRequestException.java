package io.muserver;

class InvalidRequestException extends Exception {
    final int responseCode;
    final String clientMessage;
    final String privateDetails;

    InvalidRequestException(int responseCode, String clientMessage, String privateDetails) {
        super(responseCode + " " + clientMessage + " - " + privateDetails);
        this.responseCode = responseCode;
        this.clientMessage = clientMessage;
        this.privateDetails = privateDetails;
    }
}
