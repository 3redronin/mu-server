package io.muserver.rest;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.lang.reflect.GenericDeclaration;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

/**
 * An implementation of section 3.8 of the jax-rs 2.0 spec
 */
class LegacyMediaTypeDeterminer {

    static List<MediaType> supportedProducesTypes(GenericDeclaration annotationSource) {
        Produces methodProducesAnnotation = annotationSource.getAnnotation(Produces.class);
        return methodProducesAnnotation != null
            ? LegacyMediaTypeHeaderDelegate.fromStrings(asList(methodProducesAnnotation.value()))
            : emptyList();
    }

    static List<MediaType> supportedConsumesTypes(GenericDeclaration consumableMediaTypes) {
        Consumes methodConsumesAnnotation = consumableMediaTypes.getAnnotation(Consumes.class);
        return methodConsumesAnnotation != null
            ? LegacyMediaTypeHeaderDelegate.fromStrings(asList(methodConsumesAnnotation.value()))
            : emptyList();
    }

    static List<MediaType> parseAcceptHeaders(List<String> headers) throws IllegalArgumentException {
        List<MediaType> list = new ArrayList<>();
        for (String header : headers) {
            String[] bits = header.split(",");
            for (String bit : bits) {
                if (!bit.isEmpty()) {
                    list.add(MediaType.valueOf(bit));
                }
            }
        }
        return list;
    }


    static int compareQValues(MediaType one, MediaType two) {
        double q1 = Double.parseDouble(one.getParameters().getOrDefault("q", "1.0"));
        double q2 = Double.parseDouble(two.getParameters().getOrDefault("q", "1.0"));
        int qCompare = Double.compare(q2, q1);
        if (qCompare == 0) {
            qCompare = Boolean.compare(one.isWildcardType(), two.isWildcardType());
            if (qCompare == 0) {
                qCompare = Boolean.compare(one.isWildcardSubtype(), two.isWildcardSubtype());
                if (qCompare == 0) {
                    qCompare = Integer.compare(two.getParameters().size(), one.getParameters().size());
                }
            }
        }
        return qCompare;
    }
}
