package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2OutgoingFlowController {
    private static final Logger log = LoggerFactory.getLogger(Http2OutgoingFlowController.class);

    private final int streamId;
    private int credit;
    private final Lock lock = new ReentrantLock();
    private final Condition creditAvailable = lock.newCondition();

    Http2OutgoingFlowController(int streamId, int initialCredit) {
        this.streamId = streamId;
        this.credit = initialCredit;
        log.debug("starting credit for incoming stream {} is {}", streamId, credit);
    }

    void applyWindowUpdate(Http2WindowUpdate windowUpdate) throws Http2Exception {
        int diff = windowUpdate.windowSizeIncrement();
        incrementCredit(diff);
    }

    void incrementCredit(int diff) throws Http2Exception {
        if (diff == 0) return;
        lock.lock();
        try {
            credit = Math.addExact(credit, diff);
            creditAvailable.signalAll();
            log.info("new credit for stream " + streamId + " is " + credit);
        } catch (ArithmeticException e) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow", streamId);
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

    boolean waitUntilWithdraw(int bytes, long timeout, TimeUnit unit) throws InterruptedException {
        if (bytes < 0) throw new IllegalArgumentException("Negative withdrawal");
        lock.lock();
        try {
            while (bytes > credit) {
                var timedOut = creditAvailable.await(timeout, unit);
                if (timedOut) {
                    return false;
                }
            }
            credit -= bytes;
        } finally {
            lock.unlock();
        }
        return true;
    }

    boolean withdrawIfCan(int bytes) {
        if (bytes == 0) return true;
        if (bytes < 0) throw new IllegalArgumentException("Negative withdrawal");
        lock.lock();
        try {
            if (bytes <= credit) {
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

}
