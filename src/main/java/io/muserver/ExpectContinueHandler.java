package io.muserver;

/**
 * Sends a `100-continue` if appropriate, or throws if the declared length is too long
 * (whether or not there is an `expect` header)
 */
class ExpectContinueHandler implements MuHandler {
    private final long maxRequestBodySize;

    ExpectContinueHandler(long maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        Headers h = request.headers();
        if (h.containsValue(HeaderNames.EXPECT, HeaderValues.CONTINUE, true)) {
            throwIfDeclaredSizeTooLarge(request);
            response.sendInformationalResponse(HttpStatus.CONTINUE_100, null);
        } else {
            throwIfDeclaredSizeTooLarge(request);
        }
        return false;
    }

    private void throwIfDeclaredSizeTooLarge(MuRequest request) {
        var declaredSize = request.declaredBodySize().size();
        if (declaredSize != null && declaredSize > maxRequestBodySize) {
            HttpException ex = new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
            ex.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            throw ex;
        }
    }
}
