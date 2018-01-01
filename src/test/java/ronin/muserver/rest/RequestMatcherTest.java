package ronin.muserver.rest;

import org.junit.Test;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class RequestMatcherTest {

    private final ResourceClass resourceOne = ResourceClass.fromObject(new ResourceOne());
    private final ResourceClass resourceOneV2 = ResourceClass.fromObject(new ResourceOneV2());
    private final ResourceClass resourceSomething = ResourceClass.fromObject(new ResourceSomething());
    private final ResourceClass resourceSomethingYeah = ResourceClass.fromObject(new ResourceSomethingYeah());
    private final ResourceClass resourceAnother = ResourceClass.fromObject(new ResourceAnother());
    private final ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new PeopleBelts());
    private final ResourceClass resourcePeopleBeltsInCapitals = ResourceClass.fromObject(new CapitalPeopleBelts());
    private final RequestMatcher rm = new RequestMatcher(set(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah, resourcePeopleBelts, resourcePeopleBeltsInCapitals));


    @Test(expected = NotFoundException.class)
    public void throwsIfNoValidCandidates() {
        assertThat(rm.candidateRootResourceClasses(URI.create("/api/three")).candidates, empty());
    }

    @Test
    public void findsTheMostSpecificInstanceAvailable() {
        assertThat(rm.candidateRootResourceClasses(URI.create("/api/resources/one/2")).candidates, contains(resourceOneV2));
    }

    @Test
    public void ifMultipleMatchesThenTheLongestPathRegexWins() {
        URI uri = URI.create("/api/widgets/something-else-yeah");
        assertThat(resourceSomething.matches(uri), is(false));
        assertThat(resourceSomethingYeah.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(rm.candidateRootResourceClasses(uri).candidates, contains(resourceSomethingYeah, resourceAnother));
    }

    @Test(expected = NotFoundException.class)
    public void ifJustThePrefixMatchesThenItDoesNotMatchIfThereAreNoSubResourceMethods() {
        URI uri = URI.create("/api/widgets/something-else-yeah/uhuh");
        rm.candidateRootResourceClasses(uri);
    }


    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthThenMostNamedGroupsWins() {
        URI uri = URI.create("/api/widgets/something-else");
        assertThat(resourceSomething.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(resourceSomething.pathPattern.pattern().length(), equalTo(resourceAnother.pathPattern.pattern().length()));
        assertThat(rm.candidateRootResourceClasses(uri).candidates, contains(resourceAnother, resourceSomething));
    }

    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthAndEqualNamedGroupsThenOneWithMostNonDefaultGroupsWins() {
        URI uri = URI.create("/api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.pattern().length(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.pattern().length()));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(rm.candidateRootResourceClasses(uri).candidates, contains(resourcePeopleBeltsInCapitals, resourcePeopleBelts));
    }

    @Path("/api/resources/one")
    private static class ResourceOne {
    }
    @Path("/api/resources/one/2")
    private static class ResourceOneV2 {
    }

    @Path("/api/widgets/something-else-yeah")
    private static class ResourceSomethingYeah {
    }
    @Path("/api/widgets/something-else")
    private static class ResourceSomething {
    }
    @Path("/api/widgets/{another}")
    private static class ResourceAnother {
    }

    @Path("/api/people/{name}/belts/{id}") // when {id} is expanded to default, it happens to be the same length as [A-Z]+
    private static class PeopleBelts {
    }
    @Path("/api/people/{name}/belts/{id:[A-Z]+}")
    private static class CapitalPeopleBelts {
    }


    private static Set<ResourceClass> set(ResourceClass... restResources) {
        return Stream.of(restResources).collect(Collectors.toSet());
    }

}