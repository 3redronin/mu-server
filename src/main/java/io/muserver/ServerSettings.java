package io.muserver;

import java.util.Set;

class ServerSettings {
    final long minimumGzipSize;
    final int maxHeadersSize;
    final long requestReadTimeoutMillis;
    final long maxRequestSize;
    final int maxUrlSize;
    final boolean gzipEnabled;
    final Set<String> mimeTypesToGzip;

    ServerSettings(long minimumGzipSize, int maxHeadersSize, long requestReadTimeoutMillis, long maxRequestSize, int maxUrlSize, boolean gzipEnabled, Set<String> mimeTypesToGzip) {
        this.minimumGzipSize = minimumGzipSize;
        this.maxHeadersSize = maxHeadersSize;
        this.requestReadTimeoutMillis = requestReadTimeoutMillis;
        this.maxRequestSize = maxRequestSize;
        this.maxUrlSize = maxUrlSize;
        this.gzipEnabled = gzipEnabled;
        this.mimeTypesToGzip = mimeTypesToGzip;
    }

    boolean shouldCompress(String declaredLength, String contentType) {
        if (!gzipEnabled) {
            return false;
        }
        if (declaredLength != null && Long.parseLong(declaredLength) <= minimumGzipSize) {
            return false;
        }
        if (contentType == null) {
            return false;
        }
        int i = contentType.indexOf(";");
        if (i > -1) {
            contentType = contentType.substring(0, i);
        }
        return mimeTypesToGzip.contains(contentType.trim());
    }
}
