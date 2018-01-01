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
 * An implementation of section 3.7.2 of the JAX-RS 2.0 spec.
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

    public ResourceMethod findResourceMethod(Method httpMethod, URI uri) throws NotFoundException, NotAllowedException {
        StepOneOutput stepOneOutput = stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri);
        URI methodURI = stepOneOutput.unmatchedGroup == null ? null : URI.create(stepOneOutput.unmatchedGroup);
        Set<ResourceMethod> candidateMethods = stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(methodURI, stepOneOutput.candidates);
        return stepThreeIdentifyTheMethodThatWillHandleTheRequest(httpMethod, uri, candidateMethods);
    }

    public StepOneOutput stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(URI uri) throws NotFoundException {
        List<ResourceClass> candidates = roots.stream()
            .filter(rc -> {
                PathMatch matcher = rc.pathPattern.matcher(uri);
                // Remove members that do not match U.
                // Remove members for which the final regular expression capturing group value is neither empty nor ‘/’ and the class has no subresource methods or locators.
                return matcher.matches() && !(matcher.lastGroup() != null && rc.subResourceMethods().isEmpty());
            })
            .sorted((o1, o2) -> {
                // "Sort E using the number of literal characters in each member as the primary key (descending order)"
                int c = Integer.compare(o2.pathPattern.numberOfLiterals, o1.pathPattern.numberOfLiterals);
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
        // Set Rmatch to be the first member of E and set U to be the value of the final capturing group of Rmatch when matched against U
        UriPattern rMatch = candidates.get(0).pathPattern;
        String u = rMatch.matcher(uri).lastGroup();

        // Let C0 be the set of classes Z such that R(TZ) = Rmatch. By definition, all root resource classes in C0 must be annotated with the same URI path template modulo variable names

        List<ResourceClass> c0 = candidates.stream()
            .filter(rc -> rc.pathPattern.equalModuloVariableNames(rMatch))
            .collect(toList());
        return new StepOneOutput(u, c0);
    }

    private Set<ResourceMethod> stepTwoObtainASetOfCandidateResourceMethodsForTheRequest(URI relativeUri, List<ResourceClass> candidateClasses) {
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
                int c = Integer.compare(o2.pathPattern.numberOfLiterals, o1.pathPattern.numberOfLiterals);
                if (c == 0) {
                    // "the number of capturing groups as a secondary key (descending order)"
                    c = Integer.compare(o2.pathPattern.namedGroups().size(), o1.pathPattern.namedGroups().size());
                }
                if (c == 0) {
                    // " and the number of capturing groups with non-default regular expressions (i.e. not ‘([ˆ/]+?)’) as the tertiary key (descending order)"
                    c = Integer.compare(countNonDefaultGroups(o2.pathTemplate), countNonDefaultGroups(o1.pathTemplate));
                }
                if (c == 0) {
                    // "and the source of each member as quaternary key sorting those derived from sub-resource methods ahead of those derived from sub-resource locators"
                    // ...but as locators are not currently supported, this does nothing
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

    private ResourceMethod stepThreeIdentifyTheMethodThatWillHandleTheRequest(Method method, URI uri, Set<ResourceMethod> candidates) throws NotAllowedException {
        List<ResourceMethod> result = candidates.stream().filter(rm -> rm.httpMethod == method).collect(toList());
        if (result.isEmpty()) {
            List<String> allowed = candidates.stream().map(c -> c.httpMethod.name()).distinct().collect(toList());
            throw new NotAllowedException(allowed.get(0), allowed.subList(1, allowed.size()).toArray(new String[0]));
        }
        // TODO The media type of the request entity body (if any) is a supported input data format (see Section3.5). Ifnomethodssupportthemediatypeoftherequestentitybodyanimplementation MUST generate a NotSupportedException (415 status) and no entity.
        // TODO At least one of the acceptable response entity body media types is a supported output data format (see Section 3.5). If no methods support one of the acceptable response entity body media types an implementation MUST generate a NotAcceptableException (406 status) and no entity.
        // TODO order by media types
        return result.get(0);
    }

    static class StepOneOutput {
        final String unmatchedGroup;
        final List<ResourceClass> candidates;
        StepOneOutput(String unmatchedGroup, List<ResourceClass> candidates) {
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
