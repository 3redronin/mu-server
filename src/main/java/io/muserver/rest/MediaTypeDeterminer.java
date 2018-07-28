package io.muserver.rest;

import javax.ws.rs.Consumes;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.GenericDeclaration;
import java.util.*;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toSet;

/**
 * An implementation of section 3.8 of the jax-rs 2.0 spec
 */
class MediaTypeDeterminer {
    public static MediaType determine(ObjWithType responseObject, List<MediaType> classProduces, List<MediaType> methodProduces, List<ProviderWrapper<MessageBodyWriter<?>>> messageBodyWriters, List<MediaType> clientAccepts) {

        // 1. If the method returns an instance of Response whose metadata includes the response media type (Mspecified) then set Mselected = Mspecified, finis
        if (responseObject.response != null) {
            MediaType rmt = responseObject.response.getMediaType();
            if (rmt != null) {
                return rmt;
            }
        }

        // 2. Gather the set of producible media types P
        Set<MediaType> p;
        if (!methodProduces.isEmpty()) {
            // If the method is annotated with @Produces, set P = {V (method)}where V (t) represents the values of @Produces
            // on the specified target t.
            p = new HashSet<>(methodProduces);
        } else if (!classProduces.isEmpty()) {
            // • Else if the class is annotated with @Produces, set P = {V (class)}.
            p = new HashSet<>(classProduces);
        } else {
            //  Else set P = {V (writers)} where ‘writers’ is the set of MessageBodyWriter that support the class of the
            // returned entity object.
            p = messageBodyWriters.stream()
                .filter(writer -> writer.provider.isWriteable(responseObject.type, responseObject.genericType, new Annotation[0], MediaType.WILDCARD_TYPE))
                .flatMap(writer -> writer.mediaTypes.stream())
                .collect(toSet());
        }

        // 3. If P = {}, set P = {‘*/*’}
        if (p.isEmpty()) {
            p.add(MediaType.WILDCARD_TYPE);
        }

        // 4. Obtain the acceptable media types A. If A = {}, set A = {‘*/*’}
        Set<MediaType> a = new HashSet<>(clientAccepts);
        if (a.isEmpty()) {
            a.add(MediaType.WILDCARD_TYPE);
        }

        // 5. Set M = {}
        List<CombinedMediaType> m = new ArrayList<>();
        //    For each member of A,a:
        for (MediaType mediaTypeA : a) {
            //  For each member of P,p:
            for (MediaType mediaTypeP : p) {
                //  If a is compatible with p, add S(a,p) to M, where the function S returns the most specific media
                //  type of the pair with the q-value of a and server-side qs-value of p.
                if (mediaTypeA.isCompatible(mediaTypeP)) {
                    m.add(CombinedMediaType.s(mediaTypeA, mediaTypeP));
                }
            }
        }

        // 6. If M = {} then generate a NotAcceptableException (406 status) and no entity
        if (m.isEmpty()) {
            throw new NotAcceptableException("Could not convert to a type acceptable to the client");
        }

        // 7. Sort M in descending order, with a primary key of specificity (n/m > n/* > */*), a secondary key of q-value and a tertiary key of qs-value
        m.sort(Comparator.reverseOrder());

        // 8. For each member of M,m: • If m is a concrete type, set Mselected = m, finish.
        for (CombinedMediaType mediaType : m) {
            if (mediaType.isConcrete()) {
                return new MediaType(mediaType.type, mediaType.subType, mediaType.charset);
            }
        }

        // 9. If M contains ‘*/*’ or ‘application/*’, set Mselected = ‘application/octet-stream’, finish
        for (CombinedMediaType mediaType : m) {
            if ((mediaType.isWildcardType || mediaType.type.equals("application")) && mediaType.isWildcardSubtype) {
                return MediaType.APPLICATION_OCTET_STREAM_TYPE;
            }
        }
        
        // 10. Generate a NotAcceptableException (406 status) and no entity
        throw new NotAcceptableException("Could not determine the mime type for the response. Try setting an @Produces annotation on the method.");
    }

    static List<MediaType> supportedProducesTypes(GenericDeclaration annotationSource) {
        Produces methodProducesAnnotation = annotationSource.getAnnotation(Produces.class);
        return methodProducesAnnotation != null
            ? MediaTypeHeaderDelegate.fromStrings(asList(methodProducesAnnotation.value()))
            : emptyList();
    }

    static List<MediaType> supportedConsumesTypes(GenericDeclaration consumableMediaTypes) {
        Consumes methodConsumesAnnotation = consumableMediaTypes.getAnnotation(Consumes.class);
        return methodConsumesAnnotation != null
            ? MediaTypeHeaderDelegate.fromStrings(asList(methodConsumesAnnotation.value()))
            : emptyList();
    }

    public static List<MediaType> parseAcceptHeaders(List<String> headers) {
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
}
