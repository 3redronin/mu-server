package io.muserver;

import java.io.IOException;

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
        if (h.contains(HeaderNames.EXPECT) && request.httpVersion() != HttpVersion.HTTP_1_0) {
            if (h.containsValue(HeaderNames.EXPECT, HeaderValues.CONTINUE, true)) {
                throwIfDeclaredSizeTooLarge(request);
                response.sendInformationalResponse(HttpStatus.CONTINUE_100, null);
            } else {
                throw new HttpException(HttpStatus.EXPECTATION_FAILED_417, "Unknown expectation");
            }
        } else {
            throwIfDeclaredSizeTooLarge(request);
        }
        return false;
    }

    private void throwIfDeclaredSizeTooLarge(MuRequest request) {
        var declaredSize = request.declaredBodySize().size();
        if (declaredSize != null && declaredSize > maxRequestBodySize) {
            HttpException ex = new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
            // The body is deliberately not read. HTTP/1.1 must close after this response;
            // HTTP/2 can reject just this stream without a forbidden Connection header.
            if (request.httpVersion() != HttpVersion.HTTP_2) {
                ex.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            }
            throw ex;
        }
    }
}
