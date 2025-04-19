package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class StatusLogger {
    private static final Logger log = LoggerFactory.getLogger(StatusLogger.class);
    public static void logRequests(Collection<MuRequest> muRequests) {
        int count = 0;
        for (MuRequest muRequest : muRequests) {
            Mu3Request req = (Mu3Request) muRequest;
            log.info(count + ": req " + req);
            count++;
        }
    }
}
