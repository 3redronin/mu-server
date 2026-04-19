package io.muserver;

class RequestVerifierHandler implements MuHandler {
    private RequestVerifierHandler() {}
    public static final RequestVerifierHandler INSTANCE = new RequestVerifierHandler();
    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (!request.headers().contains(HeaderNames.HOST) && request.httpVersion() != HttpVersion.HTTP_1_0) {
            throw HttpException.badRequest("No host header");
        }
        if (request.httpVersion() == HttpVersion.HTTP_1_0
            && request.headers().containsValue(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true)) {
            throw HttpException.badRequest("Chunked transfer encoding is not supported for HTTP/1.0 requests");
        }
        return false;
    }
}
