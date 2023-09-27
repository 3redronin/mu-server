package io.muserver;

import java.nio.ByteBuffer;

class DiscardingRequestBodyListener implements RequestBodyListener {
    private DiscardingRequestBodyListener() {
    }

    static RequestBodyListener INSTANCE = new DiscardingRequestBodyListener();

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        doneCallback.onComplete(null);
    }

    @Override
    public void onComplete() {
    }

    @Override
    public void onError(Throwable t) {
    }
}
