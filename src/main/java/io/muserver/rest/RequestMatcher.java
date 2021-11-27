package io.muserver.rest;

import io.muserver.HeaderNames;
import io.muserver.Method;
import io.muserver.MuRequest;

import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;
import java.net.URI;
import java.util.*;
import java.util.function.Function;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * <p>
 * An implementation of section 3.7.2 of the JAX-RS 2.0 spec.
 * </p>
 */
class RequestMatcher {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    static final List<MediaType> WILDCARD_AS_LIST = singletonList(MediaType.WILDCARD_TYPE);
    private final List<ResourceClass> roots;

    RequestMatcher(List<ResourceClass> roots) {
        if (roots == null) {
            throw new NullPointerException("roots cannot be null");
        }
        this.roots = roots;
    }

    MatchedMethod findResourceMethod(JaxRSRequest requestContext, Method httpMethod, List<MediaType> acceptHeaders, Function<MatchedMethod, ResourceClass> subResourceLocator) throws NotAllowedException, NotAcceptableException, NotSupportedException, NotMatchedException {
        String path = requestContext.relativePath();
        Set<MatchedMethod> candidateMethods = getMatchedMethodsForPath(path, subResourceLocator);
        MuRequest req = requestContext.muRequest;
        String requestBodyContentType = req.headers().get(HeaderNames.CONTENT_TYPE);
        return stepThreeIdentifyTheMethodThatWillHandleTheRequest(httpMethod, candidateMethods, requestBodyContentType, acceptHeaders);
    }

    Set<MatchedMethod> getMatchedMethodsForPath(String path, Function<MatchedMethod, ResourceClass> subResourceLocator) throws NotMatchedException {
        StepOneOutput stepOneOutput = stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(path);
        URI methodURI = stepOneOutput.unmatchedGroup == null ? null : URI.create(UriPattern.trimSlashes(stepOneOutput.unmatchedGroup));
        return stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(methodURI, stepOneOutput.candidates, subResourceLocator);
    }

    StepOneOutput stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(String uri) throws NotMatchedException {
        List<MatchedClass> candidates = roots.stream()
            .map(rc -> new MatchedClass(rc, rc.pathPattern.matcher(uri)))
            .filter(rc -> {
                PathMatch matcher = rc.pathMatch;
                // Remove members that do not match U.
                // Remove members for which the final regular expression capturing group value is neither empty nor ‘/’ and the class has no subresource methods or locators.
                return matcher.prefixMatches() && !(matcher.lastGroup() != null && rc.resourceClass.subResourceMethods().isEmpty());
            })
            .sorted((o1, o2) -> {
                UriPattern o1pp = o1.resourceClass.pathPattern;
                UriPattern o2pp = o2.resourceClass.pathPattern;
                // "Sort E using the number of literal characters in each member as the primary key (descending order)"
                int c = Integer.compare(o2pp.numberOfLiterals, o1pp.numberOfLiterals);
                if (c == 0) {
                    // "the number of capturing groups as a secondary key (descending order)"
                    c = Integer.compare(o2pp.namedGroups().size(), o1pp.namedGroups().size());
                }
                if (c == 0) {
                    // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                    c = Integer.compare(countNonDefaultGroups(o2.resourceClass.pathTemplate), countNonDefaultGroups(o1.resourceClass.pathTemplate));
                }
                return c;
            })
            .collect(toList());
        if (candidates.isEmpty()) {
            throw new NotMatchedException();
        }
        // Set Rmatch to be the first member of E and set U to be the value of the final capturing group of Rmatch when matched against U
        UriPattern rMatch = candidates.get(0).resourceClass.pathPattern;
        String u = rMatch.matcher(uri).lastGroup();

        // Let C0 be the set of classes Z such that R(TZ) = Rmatch. By definition, all root resource classes in C0 must be annotated with the same URI path template modulo variable names

        List<MatchedClass> c0 = candidates.stream()
            .filter(rc -> rc.resourceClass.pathPattern.equalModuloVariableNames(rMatch))
            .collect(toList());
        return new StepOneOutput(u, c0);
    }

    private Set<MatchedMethod> stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(URI relativeUri, List<MatchedClass> candidateClasses, Function<MatchedMethod, ResourceClass> subResourceLocator) throws NotMatchedException {
        if (relativeUri == null) {
            // handle section 3.7.2 - 2(a)
            Set<MatchedMethod> candidates = getNonLocatorMethods(candidateClasses, false);
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }

        // 2(b)  SetE={}
        List<MatchedMethod> candidates = new ArrayList<>();
        for (MatchedClass candidateClass : candidateClasses) {
            for (ResourceMethod resourceMethod : candidateClass.resourceClass.resourceMethods) {
                if (resourceMethod.isSubResource() || resourceMethod.isSubResourceLocator()) {
                    if (relativeUri != null) {
                        PathMatch matcher = resourceMethod.pathPattern.matcher(relativeUri);
                        // 2(c) add and 2(d) filter out
                        if (matcher.fullyMatches() || (resourceMethod.isSubResourceLocator() && matcher.prefixMatches())) {
                            Map<String, PathSegment> combinedParams = new HashMap<>(candidateClass.pathMatch.segments());
                            combinedParams.putAll(matcher.segments());
                            candidates.add(new MatchedMethod(candidateClass, resourceMethod, combinedParams, matcher));
                        }
                    }
                }
            }
        }

        // 2(e): If E is empty, then no matching resource can be found
        if (candidates.isEmpty()) {
            throw new NotMatchedException();
        }

        // 2(f): sort them
        candidates.sort((o1, o2) -> {
            ResourceMethod rm1 = o1.resourceMethod;
            ResourceMethod rm2 = o2.resourceMethod;
            // "Sort E using the number of literal characters4 in each member as the primary key (descending order)"
            int c = Integer.compare(rm2.pathPattern.numberOfLiterals, rm1.pathPattern.numberOfLiterals);
            if (c == 0) {
                // "the number of capturing groups as a secondary key (descending order)"
                c = Integer.compare(rm2.pathPattern.namedGroups().size(), rm1.pathPattern.namedGroups().size());
            }
            if (c == 0) {
                // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                c = Integer.compare(countNonDefaultGroups(rm2.pathTemplate), countNonDefaultGroups(rm1.pathTemplate));
            }
            if (c == 0) {
                // "and the source of each member as quaternary key sorting those derived from sub-resource methods ahead of those derived from sub-resource locators"
                // TODO: test that this is around the right way
                c = Boolean.compare(o1.resourceMethod.isSubResourceLocator(), o2.resourceMethod.isSubResourceLocator());
            }
            return c;
        });

        // 2(g) Set Rmatch to be the first member of E
        UriPattern matcher = candidates.get(0).resourceMethod.pathPattern;

        // 2(h) M = { subresource methods of all classes in C′ where R(T_method) = R_match (excluding sub-resource locators) }
        Set<MatchedMethod> m = candidates.stream()
            .filter(rm -> !rm.resourceMethod.isSubResourceLocator() && rm.resourceMethod.pathPattern.equals(matcher))
            .collect(toSet());
        if (!m.isEmpty()) {
            return m;
        }

        // 2(i) Let L be a sub-resource locator such that Rmatch = R(TL)
        Set<MatchedMethod> l = candidates.stream()
            .filter(rm -> rm.resourceMethod.isSubResourceLocator() && rm.resourceMethod.pathPattern.equals(matcher))
            .collect(toSet());
        if (l.isEmpty()) {
            throw new NotMatchedException();
        }
        if (l.size() == 1) {
            MatchedMethod mm = l.stream().findFirst().get();

            MatchedClass mc = new MatchedClass(subResourceLocator.apply(mm), mm.pathMatch);

            String remainingUrl = mm.pathMatch.lastGroup();
            //Set U to be the value of the final capturing group of R(TL) when matched against U, and set C0 to be the
            //singleton set containing only the class that defines L.
            return stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(
                remainingUrl == null ? null : URI.create(remainingUrl), Collections.singletonList(mc), subResourceLocator
            );
        }

        // Implementations SHOULD report an error if there is more than one sub-resource locator that satisfies this condition
        throw new ServerErrorException("Multiple resource locators matched", 500);
    }

    private static Set<MatchedMethod> getNonLocatorMethods(List<MatchedClass> candidateClasses, boolean isSubResource) {
        Set<MatchedMethod> candidates = new HashSet<>();
        for (MatchedClass mc : candidateClasses) {
            for (ResourceMethod resourceMethod : mc.resourceClass.resourceMethods) {
                if (resourceMethod.isSubResource() == isSubResource && !resourceMethod.isSubResourceLocator()) {
                    MatchedMethod matchedMethod = new MatchedMethod(mc, resourceMethod, mc.pathMatch.segments(), mc.pathMatch);
                    candidates.add(matchedMethod);
                }
            }
        }
        return candidates;
    }

    static class MatchedClass {
        final ResourceClass resourceClass;
        final PathMatch pathMatch;

        MatchedClass(ResourceClass resourceClass, PathMatch pathMatch) {
            this.resourceClass = resourceClass;
            this.pathMatch = pathMatch;
        }
    }

    static class MatchedMethod {
        final MatchedClass matchedClass;
        final ResourceMethod resourceMethod;
        final Map<String, PathSegment> pathParams;
        final PathMatch pathMatch;

        MatchedMethod(MatchedClass matchedClass, ResourceMethod resourceMethod, Map<String, PathSegment> pathParams, PathMatch pathMatch) {
            this.matchedClass = matchedClass;
            this.resourceMethod = resourceMethod;
            this.pathParams = pathParams;
            this.pathMatch = pathMatch;
        }

        @Override
        public String toString() {
            return "MatchedMethod{" +
                "resourceMethod=" + resourceMethod +
                ", pathParams=" + pathParams +
                '}';
        }

        public String getPathParam(String key) {
            PathSegment segment = pathParams.get(key);
            return segment == null ? null : segment.getPath();
        }
    }

    private MatchedMethod stepThreeIdentifyTheMethodThatWillHandleTheRequest(Method method, Set<MatchedMethod> candidates, String requestBodyContentType, List<MediaType> acceptHeaders) throws NotAllowedException, NotAcceptableException, NotSupportedException {
        List<MatchedMethod> result = candidates.stream().filter(rm -> rm.resourceMethod.httpMethod == method).collect(toList());
        if (result.isEmpty()) {
            List<String> allowed = candidates.stream().map(c -> c.resourceMethod.httpMethod.name()).distinct().collect(toList());
            throw new NotAllowedException(allowed.get(0), allowed.subList(1, allowed.size()).toArray(new String[0]));
        }

        // The media type of the request entity body (if any) is a supported input data format (see Section3.5).
        // If no methods support the media type of the request entity body an implementation MUST generate a
        // NotSupportedException (415 status) and no entity.
        MediaType requestBodyMediaType = requestBodyContentType == null ? MediaTypeHeaderDelegate.NONE : MediaType.valueOf(requestBodyContentType);
        result = result.stream().filter(rm -> rm.resourceMethod.canConsume(requestBodyMediaType)).collect(toList());
        if (result.isEmpty()) {
            throw new NotSupportedException();
        }

        // At least one of the acceptable response entity body media types is a supported output data format (see Section 3.5).
        // If no methods support one of the acceptable response entity body media types an implementation MUST generate a
        // NotAcceptableException (406 status) and no entity.
        List<MediaType> clientAccepts = acceptHeaders.isEmpty() ? WILDCARD_AS_LIST : acceptHeaders;
        result = result.stream().filter(rm -> rm.resourceMethod.canProduceFor(clientAccepts)).collect(toList());
        if (result.isEmpty()) {
            throw new NotAcceptableException();
        }

        if (result.size() == 1) {
            return result.get(0);
        }

        List<MediaType> requestBodyTypeAsList = Collections.singletonList(requestBodyMediaType);
        return result.stream()
            .max(Comparator.comparing((MatchedMethod o) -> bestMediaType(requestBodyTypeAsList, o.resourceMethod.effectiveConsumes))
                .thenComparing(o -> bestMediaType(clientAccepts, o.resourceMethod.effectiveProduces))).get();

    }


    private static CombinedMediaType bestMediaType(List<MediaType> requestedTypes, List<MediaType> serverProvided) {
        return serverProvided.stream()
            .map(serverType -> requestedTypes.stream().map(clientType -> CombinedMediaType.s(clientType, serverType))
                .min(Comparator.naturalOrder()).get())
            .min(Comparator.naturalOrder()).get();
    }

    static class StepOneOutput {
        final String unmatchedGroup;
        final List<RequestMatcher.MatchedClass> candidates;

        StepOneOutput(String unmatchedGroup, List<RequestMatcher.MatchedClass> candidates) {
            this.unmatchedGroup = unmatchedGroup;
            this.candidates = candidates;
        }

    }

    private int countNonDefaultGroups(String pathTemplate) {
        int count = 0;
        for (String bit : pathTemplate.split("/")) {
            if (bit.startsWith("{") && bit.endsWith("}") && bit.contains(":")) {
                count++;
            }
        }
        return count;
    }
}
