package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2FlowController {
    private static final Logger log = LoggerFactory.getLogger(Http2FlowController.class);

    private final int streamId;
    private int credit;
    private final Lock lock = new ReentrantLock();

    Http2FlowController(int streamId, int initialCredit) {
        this.streamId = streamId;
        this.credit = initialCredit;
        log.info("starting credit for stream " + streamId + " is " + credit);
    }

    void applyWindowUpdate(Http2WindowUpdate windowUpdate) throws Http2Exception {
        lock.lock();
        try {
            credit = Math.addExact(credit, windowUpdate.windowSizeIncrement());
            log.info("new credit for stream " + streamId + " is " + credit);
        } catch (ArithmeticException e) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Flow control credit overflow", streamId);
        } finally {
            lock.unlock();
        }
    }

    void applySettingsChange(Http2Settings oldSettings, Http2Settings newSettings) throws Http2Exception {
        var diff = newSettings.initialWindowSize - oldSettings.maxFrameSize;
        if (diff == 0) {
            return;
        }
        lock.lock();
        try {
            credit = Math.addExact(credit, diff);
        } catch (ArithmeticException e) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Flow control credit overflow due to settings change", streamId);
        } finally {
            lock.unlock();
        }
    }

    boolean withdrawIfCan(int bytes) {
        if (bytes == 0) return true;
        lock.lock();
        try {
            if (bytes <= credit) {
                credit -= bytes;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

}
