package io.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.LinkedList;
import java.util.Queue;

class OverlappingWriteSocketChannel implements MuSocketChannel {

    private final MuSocketChannel underlying;
    private final Queue<ChannelTask> queue = new LinkedList<>();
    private final Object lock = new Object();
    private boolean isWriting = false;

    public OverlappingWriteSocketChannel(MuSocketChannel underlying) {
        this.underlying = underlying;
    }

    @Override
    public void read(boolean useReadTimeout, CompletionHandler<Integer, Void> completionHandler) {
        underlying.read(useReadTimeout, completionHandler);
    }

    private void onTaskCompleted() throws IOException {
        synchronized (lock) {
            assert isWriting;
            isWriting = false;
            if (!queue.isEmpty()) {
                var nextTask = queue.remove();
                if (nextTask instanceof WriteTask wt) {
                    scatteringWrite(wt.srcs, wt.offset, wt.length, wt.handler);
                } else if (nextTask instanceof AbortTask) {
                    abort();
                } else if (nextTask instanceof CloseTask ct) {
                    close(ct.callback);
                }
            }
        }
    }

    @Override
    public void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler) {
        synchronized (lock) {
            if (isWriting) {
                queue.add(new WriteTask(srcs, offset, length, handler));
            } else {
                isWriting = true;
                underlying.scatteringWrite(srcs, offset, length, new CompletionHandler<>() {
                    @Override
                    public void completed(Long result, Void attachment) {
                        try {
                            onTaskCompleted();
                            handler.completed(result, attachment);
                        } catch (IOException e) {
                            handler.failed(e, attachment);
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        isWriting = false;
                        handler.failed(exc, attachment);
                    }
                });
            }
        }
    }

    @Override
    public ByteBuffer readBuffer() {
        return underlying.readBuffer();
    }

    @Override
    public boolean isOpen() {
        return underlying.isOpen();
    }

    @Override
    public void close(DoneCallback callback) {
        synchronized (lock) {
            if (isWriting) {
                queue.add(new CloseTask(callback));
            } else {
                underlying.close(callback);
            }
        }
    }

    @Override
    public void abort() throws IOException {
        underlying.abort();
    }

    @Override
    public boolean isTls() {
        return underlying.isTls();
    }

    private interface ChannelTask {}
    private record WriteTask(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler) implements ChannelTask { }
    private record AbortTask() implements ChannelTask {}
    private record CloseTask(DoneCallback callback) implements ChannelTask {}
}
