package io.muserver;

import io.netty.handler.traffic.TrafficCounter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class MuStatsImpl implements MuStats {
    private final TrafficCounter trafficCounter;
    private final AtomicLong completedRequests = new AtomicLong(0);
    private final AtomicLong invalidHttpRequests = new AtomicLong(0);
    private final Set<MuRequest> activeRequests = ConcurrentHashMap.newKeySet();

    MuStatsImpl(TrafficCounter trafficCounter) {
        this.trafficCounter = trafficCounter;
    }

    @Override
    public long activeConnections() {
        return activeRequests.size();
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
    public Set<MuRequest> activeRequests() {
        return activeRequests;
    }

    void onRequestStarted(MuRequest request) {
        activeRequests.add(request);
    }
    void onRequestEnded(MuRequest request) {
        activeRequests.remove(request);
        completedRequests.incrementAndGet();
    }
    void onInvalidRequest() {
        invalidHttpRequests.incrementAndGet();
    }

    @Override
    public String toString() {
        return "Completed requests: " + completedRequests() + "; active: " + activeConnections() + "; invalid requests: " + invalidHttpRequests() + "; bytes received: " + bytesRead() + "; bytes sent: " + bytesSent();
    }
}
