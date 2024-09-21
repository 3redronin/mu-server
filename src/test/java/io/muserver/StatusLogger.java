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
            var info = req.response;
            log.info(count + ": exchange state "+ info.status() + " with duration " + info.duration() + "ms. Req: " + req + " with response " + info.response() + " ");
            count++;
        }
    }
}
