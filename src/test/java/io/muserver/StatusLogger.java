package io.muserver;


import java.util.Collection;

public class StatusLogger {
    public static void logRequests(Collection<MuRequest> muRequests) {
        int count = 0;
        for (MuRequest muRequest : muRequests) {
            Mu3Request req = (Mu3Request) muRequest;
            count++;
        }
    }
}
