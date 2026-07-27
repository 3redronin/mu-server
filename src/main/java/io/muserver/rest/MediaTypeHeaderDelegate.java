package io.muserver.rest;

import io.muserver.MediaTypeParser;
import io.muserver.Mutils;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class MediaTypeHeaderDelegate implements RuntimeDelegate.HeaderDelegate<MediaType> {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    static final MediaType NONE = new MediaType("-", "-");


    @Override
    public MediaType fromString(String value) {
        Mutils.notNull("value", value);
        return MediaTypeParser.fromString(value);
    }

    @Override
    public String toString(MediaType mediaType) {
        Mutils.notNull("mediaType", mediaType);
        return MediaTypeParser.toString(mediaType);
    }

    static List<MediaType> fromStrings(@Nullable List<String> accepts) {
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

    // MediaType only treats a bare "*" subtype as a wildcard, while the standard XML entity providers
    // are required to support media types of the form application/*+xml.
    static boolean isCompatible(MediaType first, MediaType second) {
        if (first.isCompatible(second)) {
            return true;
        }
        boolean typesCompatible = first.getType().equalsIgnoreCase(second.getType())
            || first.isWildcardType()
            || second.isWildcardType();
        return typesCompatible
            && (suffixWildcardMatches(first.getSubtype(), second.getSubtype())
            || suffixWildcardMatches(second.getSubtype(), first.getSubtype()));
    }

    static boolean isWildcardSubtype(String subtype) {
        return "*".equals(subtype) || (subtype.length() > 2 && subtype.startsWith("*+"));
    }

    private static boolean suffixWildcardMatches(String pattern, String concrete) {
        return pattern.length() > 2
            && pattern.startsWith("*+")
            && concrete.length() > pattern.length() - 1
            && concrete.regionMatches(true, concrete.length() - pattern.length() + 1,
            pattern, 1, pattern.length() - 1);
    }

    static boolean atLeastOneCompatible(List<MediaType> providerProduces, List<MediaType> consumerAccepts, @Nullable String checkParameter) {
        for (MediaType clientAccept : consumerAccepts) {
            for (MediaType produce : providerProduces) {
                boolean compatible = isCompatible(produce, clientAccept);
                if (compatible) {
                    if (checkParameter != null) {
                        String clientParam = clientAccept.getParameters().get(checkParameter);
                        if (clientParam != null) {
                            @Nullable String serverParam = produce.getParameters().get(checkParameter);
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
