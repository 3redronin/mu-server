package io.muserver;

class RequestVerifierHandler implements MuHandler {
    private RequestVerifierHandler() {}
    public static final RequestVerifierHandler INSTANCE = new RequestVerifierHandler();
    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        if (!request.headers().contains(HeaderNames.HOST)) {
            throw HttpException.badRequest("No host header");
        }
        return false;
    }
}
