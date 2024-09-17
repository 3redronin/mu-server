package io.muserver;

class ExpectContinueHandler implements MuHandler {
    private final long maxRequestBodySize;

    ExpectContinueHandler(long maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        Headers h = request.headers();
        if (h.containsValue(HeaderNames.EXPECT, HeaderValues.CONTINUE, true)) {
            var declaredSize = h.getLong(HeaderNames.CONTENT_LENGTH.toString(), 0L);
            if (declaredSize > maxRequestBodySize) {
                HttpException ex = new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
                ex.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                throw ex;
            }
            response.sendInformationalResponse(HttpStatus.CONTINUE_100, null);
        }
        return false;
    }
}
