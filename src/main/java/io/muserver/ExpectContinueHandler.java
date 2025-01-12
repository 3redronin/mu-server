package io.muserver;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;

/**
 * Sends a `100-continue` if appropriate, or throws if the declared length is too long
 * (whether or not there is an `expect` header)
 */
@NullMarked
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
                throwIfDeclaredSizeTooLarge(request, false);
                response.sendInformationalResponse(HttpStatus.CONTINUE_100, null);
            } else {
                throw new HttpException(HttpStatus.EXPECTATION_FAILED_417, "Unknown expectation");
            }
        } else {
            throwIfDeclaredSizeTooLarge(request, true);
        }
        return false;
    }

    private void throwIfDeclaredSizeTooLarge(MuRequest request, boolean consumeBody) throws IOException {
        var declaredSize = request.declaredBodySize().size();
        if (declaredSize != null && declaredSize > maxRequestBodySize) {
            // one has to consume the body for it to be a valid HTTP response
            if (consumeBody) {
                try (var body = request.body()) {
                    var buf = new byte[8192];
                    while (body.read(buf) != -1) {
                        // ignore it
                    }
                }
            }

            HttpException ex = new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
            ex.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            throw ex;
        }
    }
}
