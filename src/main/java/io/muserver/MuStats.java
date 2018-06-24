package io.muserver;

import java.util.Set;

public interface MuStats {

    long activeConnections();

    long completedRequests();

    long invalidHttpRequests();

    long bytesSent();

    long bytesRead();

    Set<MuRequest> activeRequests();
}
