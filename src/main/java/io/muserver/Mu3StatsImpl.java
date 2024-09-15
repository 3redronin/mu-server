package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class Mu3StatsImpl implements MuStats {
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong completedRequests = new AtomicLong(0);
    private final AtomicLong invalidHttpRequests = new AtomicLong(0);
    private final AtomicLong rejectedDueToOverload = new AtomicLong(0);
    private final AtomicLong failedToConnect = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong bytesRead = new AtomicLong(0);
    private final Set<MuRequest> activeRequests = ConcurrentHashMap.newKeySet();

    @Override
    public long completedConnections() {
        return totalConnections.get();
    }

    @Override
    public long activeConnections() {
        return activeConnections.get();
    }

    @Override
    public long completedRequests() {
        return completedRequests.get();
    }

    @Override
    public long invalidHttpRequests() {
        return invalidHttpRequests.get();
    }

    @Override
    public long bytesSent() {
        return bytesSent.get();
    }

    @Override
    public long bytesRead() {
        return bytesRead.get();
    }

    @Override
    public long rejectedDueToOverload() {
        return rejectedDueToOverload.get();
    }

    @Override
    public long failedToConnect() {
        return failedToConnect.get();
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return Collections.unmodifiableSet(activeRequests);
    }


    void onRequestStarted(MuRequest request) {
        activeRequests.add(request);
    }

    private static final Logger log = LoggerFactory.getLogger(Mu3StatsImpl.class);
    void onRequestEnded(MuRequest request) {
        if (activeRequests.remove(request)) {
            completedRequests.incrementAndGet();
        } else {
            log.info("Asked to remove " + request + " but it wasn't active");
        }
    }

    void onRejectedDueToOverload() {
        rejectedDueToOverload.incrementAndGet();
    }

    void onInvalidRequest() {
        invalidHttpRequests.incrementAndGet();
    }

    void onFailedToConnect() {
        failedToConnect.incrementAndGet();
    }

    void onConnectionOpened() {
        activeConnections.incrementAndGet();
    }

    void onConnectionClosed() {
        activeConnections.decrementAndGet();
        totalConnections.incrementAndGet();
    }


    void onBytesRead(long bytes) {
        bytesRead.addAndGet(bytes);
    }
    void onBytesSent(long bytes) {
        bytesSent.addAndGet(bytes);
    }

    @Override
    public String toString() {
        return "Active requests: " + activeRequests().size() + "; completed requests: " + completedRequests() +
            "; active connections: " + activeConnections() + "; completed connections: " + completedConnections() +
            "; invalid requests: " + invalidHttpRequests() + "; bytes received: " + bytesRead() +
            "; bytes sent: " + bytesSent() + "; rejected: " + rejectedDueToOverload() +
            "; connectionFailured: " + failedToConnect();
    }
}
