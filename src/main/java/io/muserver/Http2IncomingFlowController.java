package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2IncomingFlowController {
    private static final Logger log = LoggerFactory.getLogger(Http2IncomingFlowController.class);

    private final int streamId;
    /**
     * The number of bytes the client can still send us
     */
    private int credit;
    /**
     * The maximum possible credit on this stream/connection
     */
    private int maxCredit;
    /**
     * The amount of credit that the client could send, but we haven't told them yet
     */
    private int pending;
    private final Lock lock = new ReentrantLock();

    Http2IncomingFlowController(int streamId, int initialCredit) {
        this.streamId = streamId;
        this.credit = initialCredit;
        this.maxCredit = initialCredit;
        log.debug("starting credit for outgoing stream {} is {}", streamId, credit);
    }

    void applySettingsChange(Http2Settings oldSettings, Http2Settings newSettings) throws Http2Exception {
        var diff = newSettings.initialWindowSize - oldSettings.initialWindowSize;
        if (diff > 0) {
            lock.lock();
            try {
                credit += diff; // can't overflow
                maxCredit = newSettings.initialWindowSize;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Increments the credit available, and returns an int telling the caller by how
     * much to notify the client with in a WindowUpdate (or 0 not to update).
     * @param diff The amount of credit to increment by
     * @return The amount of bytes to send in a window update, if any
     * @throws Http2Exception Credit exceeds 2^31-1
     */
    int incrementCredit(int diff) throws Http2Exception {
        if (diff == 0) return 0;
        lock.lock();
        try {
            credit = Math.addExact(credit, diff);
            pending += credit;
            log.info("new credit for stream " + streamId + " is " + credit);
            if (pending >= (maxCredit >>> 1)) {
                // we have used more than half of the available credit - send an update
                var commit = pending;
                pending = 0;
                log.info("Time to commit credit for outgoing stream " + streamId + " is " + commit);
                return commit;
            } else {
                return 0;
            }
        } catch (ArithmeticException e) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow", streamId);
        } finally {
            lock.unlock();
        }
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

interface CreditAvailableListener {
    void creditAvailable(int credit) throws Http2Exception;
}
