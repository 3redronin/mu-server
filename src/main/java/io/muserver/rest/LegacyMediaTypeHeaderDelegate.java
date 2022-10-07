package io.muserver.rest;

import io.muserver.LegacyMediaTypeParser;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class LegacyMediaTypeHeaderDelegate implements RuntimeDelegate.HeaderDelegate<MediaType> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    static final MediaType NONE = new MediaType("-", "-");


    @Override
    public MediaType fromString(String value) {
        return LegacyMediaTypeParser.fromString(value);
    }

    @Override
    public String toString(MediaType mediaType) {
        return LegacyMediaTypeParser.toString(mediaType);
    }

    static List<MediaType> fromStrings(List<String> accepts) {
        if (accepts == null || accepts.isEmpty()) {
            return Collections.emptyList();
        }
        List<MediaType> results = new ArrayList<>();
        for (String acceptMess : accepts) {
            for (String accept : acceptMess.split(",")) {
                results.add(MediaType.valueOf(accept.trim()));
            }
        }
        return results;
    }

    static boolean atLeastOneCompatible(List<MediaType> providerProduces, List<MediaType> consumerAccepts, String checkParameter) {
        for (MediaType clientAccept : consumerAccepts) {
            for (MediaType produce : providerProduces) {
                boolean compatible = produce.isCompatible(clientAccept);
                if (compatible) {
                    if (checkParameter != null) {
                        String clientParam = clientAccept.getParameters().get(checkParameter);
                        if (clientParam != null) {
                            String serverParam = produce.getParameters().get(checkParameter);
                            compatible = clientParam.equalsIgnoreCase(serverParam);
                        }
                    }
                    if (compatible) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
