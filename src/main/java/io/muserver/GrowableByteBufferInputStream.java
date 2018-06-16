package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class GrowableByteBufferInputStream extends InputStream {

	private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);
	private static final ByteBuffer LAST = ByteBuffer.allocate(0);
	private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>(); // TODO add config for this which is like a request size upload limit (sort of)
	private volatile ByteBuffer current = EMPTY;
    private RequestBodyListener listener;
    private final Object listenerLock = new Object();

    private ByteBuffer cycleIfNeeded() throws IOException {
		if (current == LAST) {
			return current;
		}
		synchronized (queue) {
			ByteBuffer cur = current;
			if (!cur.hasRemaining()) {
				try {
					current = queue.poll(120, TimeUnit.SECONDS); // TODO: add config for this which is like a request stream idle timeout limit
                    cur = current;
				} catch (InterruptedException e) {
					// given the InputStream API, is this the way to handle interuptions?
					throw new IOException("Thread was interrupted");
				}
			}
			return cur;
		}
	}

	public int read() throws IOException {
		ByteBuffer cur = cycleIfNeeded();
		if (cur == LAST) {
			return -1;
		}
		return cur.get() & 0xff;
	}

	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	public int read(byte[] b, int off, int len) throws IOException {
		ByteBuffer cur = cycleIfNeeded();
		if (cur == LAST) {
			return -1;
		}
		int toRead = Math.min(len, cur.remaining());
		cur.get(b, off, toRead);
		return toRead;
	}

	public int available() throws IOException {
		ByteBuffer cur = cycleIfNeeded();
		return cur.remaining();
	}

	public void close() throws IOException {
		// This is called from the main netty accepter thread so must be non-blocking
        synchronized (listenerLock) {
            if (listener == null) {
                queue.add(LAST);
            } else {
                sendToListener(listener, LAST);
            }
        }
	}

	void handOff(ByteBuffer buffer) {
		// This is called from the main netty accepter thread so must be non-blocking
        synchronized (listenerLock) {
            if (listener == null) {
                queue.add(buffer);
            } else {
                sendToListener(listener, buffer);
            }
        }
	}

    void switchToListener(RequestBodyListener readListener) {
	    synchronized (listenerLock) {
	        while (!queue.isEmpty()) {
                ArrayList<ByteBuffer> existing = new ArrayList<>(queue.size());
                queue.drainTo(existing);
                for (ByteBuffer byteBuffer : existing) {
                    sendToListener(readListener, byteBuffer);
                }
            }
            this.listener = readListener;
        }
    }

    private static void sendToListener(RequestBodyListener readListener, ByteBuffer byteBuffer) {
        if (byteBuffer == LAST) {
            readListener.onComplete();
        } else {
            readListener.onDataReceived(byteBuffer);
        }
    }
}
