package io.muserver;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class MuStatsImpl2 implements MuStats {

    private final AtomicLong active = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong bytesRead = new AtomicLong();
    private final Set<MuRequest> activeRequests = ConcurrentHashMap.newKeySet();


    @Override
    public long activeConnections() {
        return active.get();
    }

    @Override
    public long completedRequests() {
        return completed.get();
    }

    @Override
    public long invalidHttpRequests() {
        return invalid.get();
    }

    @Override
    public long bytesSent() {
        return bytesRead.get();
    }

    @Override
    public long bytesRead() {
        return bytesSent.get();
    }

    @Override
    public long currentRequests() {
        return activeRequests.size();
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return activeRequests;
    }



    public void incrementActiveConnections() {
        active.incrementAndGet();
    }

    public void decrementActiveConnections() {
        active.decrementAndGet();
    }

    void onRequestStarted(MuRequest request) {
        activeRequests.add(request);
    }
    void onRequestEnded(MuRequest request) {
        boolean removed = activeRequests.remove(request);
        if (removed) {
            completed.incrementAndGet();
        }
    }

    public void incrementInvalidHttpRequests() {
        invalid.incrementAndGet();
    }

    public void incrementBytesSent(long num) {
        bytesSent.addAndGet(num);
    }

    public void incrementBytesRead(long num) {
        bytesRead.addAndGet(num);
    }
}
