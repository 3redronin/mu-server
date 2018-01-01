package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

/**
 * <p>
 * An implementation of section 3.7.2 of the JAX-RS 1.1 spec.
 * </p>
 * <p>
 * Note that some of part 2 of this section, i.e. "Obtain the object that will handle the request and a set of candidate methods"
 * is skipped due to the fact that sub-resource locators are not supported in this implementation.
 * </p>
 */
class RequestMatcher {

    static {
        MuRuntimeDelegate.ensureSet();
    }

    private final Set<ResourceClass> roots;

    RequestMatcher(Set<ResourceClass> roots) {
        if (roots == null) {
            throw new NullPointerException("roots cannot be null");
        }
        this.roots = roots;
    }

    public ResourceMethod findResourceMethod(Method method, URI uri) throws NotFoundException {
        StepOneOutput stepOneOutput = candidateRootResourceClasses(uri);
        URI methodURI = stepOneOutput.unmatchedGroup == null ? null : URI.create(stepOneOutput.unmatchedGroup);
        Set<ResourceMethod> candidateMethods = candidateResourceMethods(methodURI, stepOneOutput.candidates);
        return identifyMethodThatWillHandleTheRequest(method, uri, candidateMethods);
    }

    private ResourceMethod identifyMethodThatWillHandleTheRequest(Method method, URI uri, Set<ResourceMethod> candidates) {
        List<ResourceMethod> result = candidates.stream().filter(rm -> rm.httpMethod == method).collect(toList());
        if (result.isEmpty()) {
            throw new NotAllowedException(uri + " does not support " + method + " requests");
        }
        // TODO The media type of the request entity body (if any) is a supported input data format (see Section3.5). Ifnomethodssupportthemediatypeoftherequestentitybodyanimplementation MUST generate a NotSupportedException (415 status) and no entity.
        // TODO At least one of the acceptable response entity body media types is a supported output data format (see Section 3.5). If no methods support one of the acceptable response entity body media types an implementation MUST generate a NotAcceptableException (406 status) and no entity.
        // TODO order by media types
        return result.get(0);
    }

    public Set<ResourceMethod> candidateResourceMethods(URI relativeUri, List<ResourceClass> candidateClasses) {
        if (relativeUri == null) {
            Set<ResourceMethod> candidates = candidateClasses.stream()
                .flatMap(resourceClass -> resourceClass.resourceMethods.stream()
                    .filter(resourceMethod -> !resourceMethod.isSubResource()))
                .collect(toSet());
            if (!candidates.isEmpty()) {
                return candidates;
            }
        }
        List<ResourceMethod> candidates = candidateClasses.stream()
            .flatMap(rc -> rc.resourceMethods.stream().filter(ResourceMethod::isSubResource))
            .filter(rm -> {
                PathMatch matcher = rm.pathPattern.matcher(relativeUri);
                return matcher.matches() && matcher.lastGroup() == null;
            })
            .sorted((o1, o2) -> {
                // "Sort E using the number of literal characters4 in each member as the primary key (descending order)"
                int c = Integer.compare(o2.pathPattern.pattern().length(), o1.pathPattern.pattern().length());
                if (c == 0) {
                    // "the number of capturing groups as a secondary key (descending order)"
                    c = Integer.compare(o2.pathPattern.namedGroups().size(), o1.pathPattern.namedGroups().size());
                }
                if (c == 0) {
                    // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                    c = Integer.compare(countNonDefaultGroups(o2.pathTemplate), countNonDefaultGroups(o1.pathTemplate));
                }
                return c;
            })
            .collect(toList());

        if (candidates.isEmpty()) {
            throw new NotFoundException();
        }

        UriPattern matcher = candidates.get(0).pathPattern;
        Set<ResourceMethod> m = candidates.stream().filter(rm -> rm.pathPattern.equals(matcher)).collect(toSet());
        if (!m.isEmpty()) {
            return m;
        }

        throw new NotFoundException();
    }

    static class StepOneOutput {
        final String unmatchedGroup;
        final List<ResourceClass> candidates;

        StepOneOutput(String unmatchedGroup, List<ResourceClass> candidates) {
            this.unmatchedGroup = unmatchedGroup;
            this.candidates = candidates;
        }
    }

    public StepOneOutput candidateRootResourceClasses(URI uri) throws NotFoundException {
        List<ResourceClass> candidates = roots.stream()
            .filter(rc -> {
                PathMatch matcher = rc.pathPattern.matcher(uri);
                return matcher.matches() && !(matcher.lastGroup() != null && rc.subResourceMethods().isEmpty());
            })
            .sorted((o1, o2) -> {
                // "Sort E using the number of literal characters4 in each member as the primary key (descending order)"
                int c = Integer.compare(o2.pathPattern.pattern().length(), o1.pathPattern.pattern().length());
                if (c == 0) {
                    // "the number of capturing groups as a secondary key (descending order)"
                    c = Integer.compare(o2.pathPattern.namedGroups().size(), o1.pathPattern.namedGroups().size());
                }
                if (c == 0) {
                    // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                    c = Integer.compare(countNonDefaultGroups(o2.pathTemplate), countNonDefaultGroups(o1.pathTemplate));
                }
                return c;
            })
            .collect(toList());
        if (candidates.isEmpty()) {
            throw new NotFoundException();
        }
        PathMatch rMatch = candidates.get(0).pathPattern.matcher(uri);
        return new StepOneOutput(rMatch.lastGroup(), candidates);
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
