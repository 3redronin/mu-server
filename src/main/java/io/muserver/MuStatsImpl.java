package io.muserver;

import io.netty.handler.traffic.TrafficCounter;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class MuStatsImpl implements MuStats {
    private final TrafficCounter trafficCounter;
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong completedRequests = new AtomicLong(0);
    private final AtomicLong invalidHttpRequests = new AtomicLong(0);
    private final AtomicLong rejectedDueToOverload = new AtomicLong(0);
    private final AtomicLong failedToConnect = new AtomicLong(0);
    private final Set<MuRequest> activeRequests = ConcurrentHashMap.newKeySet();

    MuStatsImpl(TrafficCounter trafficCounter) {
        this.trafficCounter = trafficCounter;
    }

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
        return trafficCounter.cumulativeWrittenBytes();
    }

    @Override
    public long bytesRead() {
        return trafficCounter.cumulativeReadBytes();
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

    void onRequestEnded(MuRequest request) {
        if (activeRequests.remove(request)) {
            completedRequests.incrementAndGet();
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

    @Override
    public String toString() {
        return "Active requests: " + activeRequests().size() + "; completed requests: " + completedRequests() +
            "; active connections: " + activeConnections() + "; completed connections: " + completedConnections() +
            "; invalid requests: " + invalidHttpRequests() + "; bytes received: " + bytesRead() +
            "; bytes sent: " + bytesSent() + "; rejected: " + rejectedDueToOverload() +
            "; connectionFailured: " + failedToConnect();
    }
}
