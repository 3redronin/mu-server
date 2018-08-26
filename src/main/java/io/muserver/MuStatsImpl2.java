package io.muserver;

import java.util.Collections;
import java.util.Set;

public class MuStatsImpl2 implements MuStats {
    @Override
    public long activeConnections() {
        return 0;
    }

    @Override
    public long completedRequests() {
        return 0;
    }

    @Override
    public long invalidHttpRequests() {
        return 0;
    }

    @Override
    public long bytesSent() {
        return 0;
    }

    @Override
    public long bytesRead() {
        return 0;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return Collections.emptySet();
    }
}
