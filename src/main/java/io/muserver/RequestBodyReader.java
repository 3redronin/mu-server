package io.muserver;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

interface RequestBodyReader {
    void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback);

    class ListenerAdapter implements RequestBodyReader {
        private static final Logger log = LoggerFactory.getLogger(ListenerAdapter.class);
        private final RequestBodyListener readListener;

        public ListenerAdapter(RequestBodyListener readListener) {
            this.readListener = readListener;
        }

        @Override
        public void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                if (content.readableBytes() > 0) {
                    DoneCallback successCalled = !last ? callback : error -> {
                        if (error == null) {
                            readListener.onComplete();
                        } else {
                            readListener.onError(error);
                        }
                        callback.onComplete(error);
                    };
                    readListener.onDataReceived(content.nioBuffer(), successCalled);
                } else if (last) {
                    log.info("Got empty last message");
                    readListener.onComplete();
                    callback.onComplete(null);
                }
            } catch (Exception e) {
                try {
                    callback.onComplete(e);
                } catch (Exception ignored) {
                } finally {
                    readListener.onError(e);
                }
            }
        }
    }

    class DiscardingReader implements RequestBodyReader {
        @Override
        public void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                callback.onComplete(null);
            } catch (Exception ignored) { }
        }
    }

}
