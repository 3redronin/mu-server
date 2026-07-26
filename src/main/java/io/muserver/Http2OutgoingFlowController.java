package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2OutgoingFlowController {
    private static final Logger log = LoggerFactory.getLogger(Http2OutgoingFlowController.class);

    private final int streamId;
    private int credit;
    private boolean terminated;
    private final Lock lock = new ReentrantLock();

    Http2OutgoingFlowController(int streamId, int initialCredit) {
        this.streamId = streamId;
        this.credit = initialCredit;
        log.debug("starting credit for incoming stream {} is {}", streamId, credit);
    }

    void applyWindowUpdate(Http2WindowUpdate windowUpdate) throws Http2Exception {
        int diff = windowUpdate.windowSizeIncrement();
        incrementCredit(diff);
    }

    void applySettingsChange(Http2Settings oldSettings, Http2Settings newSettings) throws Http2Exception {
        int diff = newSettings.initialWindowSize - oldSettings.initialWindowSize;
        if (diff == 0) return;
        incrementCredit(diff);
    }

    void incrementCredit(int diff) throws Http2Exception {
        if (diff == 0) return;
        lock.lock();
        try {
            credit = Math.addExact(credit, diff);
            log.info("new credit for stream " + streamId + " is " + credit);
        } catch (ArithmeticException e) {
            throw streamId == 0
                ? Http2Exception.connection(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow")
                : Http2Exception.stream(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow", streamId);
        } finally {
            lock.unlock();
        }
    }

    int credit() {
        lock.lock();
        try {
            return credit;
        } finally {
            lock.unlock();
        }
    }

    boolean withdrawIfCan(int bytes) {
        if (bytes == 0) return true;
        if (bytes < 0) throw new IllegalArgumentException("Negative withdrawal");
        lock.lock();
        try {
            if (!terminated && bytes <= credit) {
                log.info("withdrawing " + bytes + " bytes from " + streamId);
                if (bytes == 65535) {
                    log.info("hmmm");
                }
                credit -= bytes;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    int withdrawUpTo(int maximumBytes) {
        if (maximumBytes < 0) throw new IllegalArgumentException("Negative withdrawal");
        if (maximumBytes == 0) return 0;
        lock.lock();
        try {
            if (terminated || credit <= 0) {
                return 0;
            }
            int withdrawn = Math.min(maximumBytes, credit);
            credit -= withdrawn;
            log.info("withdrawing {} bytes from {}", withdrawn, streamId);
            return withdrawn;
        } finally {
            lock.unlock();
        }
    }

    void terminate() {
        lock.lock();
        try {
            terminated = true;
        } finally {
            lock.unlock();
        }
    }

    boolean terminated() {
        lock.lock();
        try {
            return terminated;
        } finally {
            lock.unlock();
        }
    }

}
