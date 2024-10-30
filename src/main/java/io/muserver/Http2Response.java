package io.muserver;

import java.io.OutputStream;

public class Http2Response extends BaseResponse {

    private final Mu3Request request;

    Http2Response(Headers headers, Mu3Request request) {
        super(headers);
        this.request = request;
    }

    @Override
    protected void cleanup() {
        
    }

    @Override
    public OutputStream outputStream(int bufferSize) {
        return null;
    }
}
