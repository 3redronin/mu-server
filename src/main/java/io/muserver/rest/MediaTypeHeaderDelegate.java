package io.muserver.rest;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.RuntimeDelegate;
import java.util.*;

class MediaTypeHeaderDelegate implements RuntimeDelegate.HeaderDelegate<MediaType> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    public static final MediaType NONE = new MediaType("-", "-");


    @Override
    public MediaType fromString(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        int slashIndex = value.indexOf('/');
        String type = value.substring(0, slashIndex).trim();
        Map<String, String> params;
        String subType;
        int firstSemi = value.indexOf(';', slashIndex);
        if (firstSemi == -1) {
            params = null;
            subType = value.substring(slashIndex + 1).trim();
        } else {
            subType = value.substring(slashIndex + 1, firstSemi).trim();

            String[] bits = value.substring(firstSemi + 1).split(";");
            params = new HashMap<>();
            for (String bit : bits) {
                String[] pair = bit.split("=");
                params.put(pair[0].trim(), pair[1].trim());
            }
        }

        return new MediaType(type, subType, params);
    }

    @Override
    public String toString(MediaType mediaType) {
        StringBuilder sb = new StringBuilder(mediaType.getType() + "/" + mediaType.getSubtype());
        Map<String, String> parameters = mediaType.getParameters();
        if (parameters != null) {
            parameters.forEach((key, value) -> sb.append(';').append(key).append('=').append(value));
        }
        return sb.toString();
    }

    public static List<MediaType> fromStrings(List<String> accepts) {
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

    public static boolean atLeastOneCompatible(List<MediaType> providerProduces, List<MediaType> consumerAccepts) {
        for (MediaType clientAccept : consumerAccepts) {
            for (MediaType produce : providerProduces) {
                boolean compatible = produce.isCompatible(clientAccept);
                if (compatible) {
                    return true;
                }
            }
        }
        return false;
    }
}
