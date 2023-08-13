package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class StatusLogger {
    private static final Logger log = LoggerFactory.getLogger(StatusLogger.class);
    public static void logRequests(Collection<MuRequest> muRequests) {
        int count = 0;
        var now = System.currentTimeMillis();
        for (MuRequest muRequest : muRequests) {
            MuRequestImpl req = (MuRequestImpl) muRequest;
            long duration = now - req.startTime();
            log.info(count + ": request state "+ req.requestState() + " with duration " + duration + "ms. Req: " + req + " (status " + req.requestState() + ")");
            count++;
        }
    }
}
