package io.muserver;

import io.muserver.handlers.ResourceType;

import java.util.Set;

class CompressionSettings {

    boolean gzipEnabled = true;
    long minimumGzipSize = 1400;
    Set<String> mimeTypesToGzip = ResourceType.gzippableMimeTypes(ResourceType.getResourceTypes());

    boolean shouldGzip(long size, String contentType, String acceptEncoding) {
        if (contentType == null || acceptEncoding == null) {
            return false;
        }
        boolean contentIsOkay = gzipEnabled && size >= minimumGzipSize && mimeTypesToGzip.contains(contentType.split(";", 2)[0]);
        if (!contentIsOkay) {
            return false;
        }
        String[] types = acceptEncoding.split(",(\\s)*");
        for (String type : types) {
            if (type.equalsIgnoreCase("gzip")) {
                return true;
            }
        }
        return false;
    }

}
