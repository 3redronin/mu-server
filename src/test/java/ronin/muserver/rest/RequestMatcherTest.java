package ronin.muserver.rest;

import org.junit.Assert;
import org.junit.Test;
import ronin.muserver.Method;

import javax.ws.rs.*;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static ronin.muserver.rest.ResourceClass.fromObject;

public class RequestMatcherTest {

    private final ResourceClass resourceOne = fromObject(new ResourceOne());
    private final ResourceClass resourceOneV2 = fromObject(new ResourceOneV2());
    private final ResourceClass resourceSomething = fromObject(new ResourceSomething());
    private final ResourceClass resourceSomethingYeah = fromObject(new ResourceSomethingYeah());
    private final ResourceClass resourceAnother = fromObject(new ResourceAnother());
    private final RequestMatcher rm = new RequestMatcher(set(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah));

    @Test(expected = NotFoundException.class)
    public void throwsIfNoValidCandidates() {
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(URI.create("api/three")).candidates, empty());
    }

    @Test
    public void findsTheMostSpecificInstanceAvailable() {
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(URI.create("api/resources/one/2")).candidates,
            contains(resourceOneV2));
    }

    @Test
    public void ifMultipleMatchesThenTheLongestPathRegexWins() {
        URI uri = URI.create("api/widgets/something-else-yeah");
        assertThat(resourceSomething.matches(uri), is(false));
        assertThat(resourceSomethingYeah.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri).candidates,
            contains(resourceSomethingYeah));
    }

    @Test(expected = NotFoundException.class)
    public void ifJustThePrefixMatchesThenItDoesNotMatchIfThereAreNoSubResourceMethods() {
        URI uri = URI.create("/api/widgets/something-else-yeah/uhuh");
        rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri);
    }


    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthThenMostNamedGroupsWins() {

        @Path("/api/widgets/b")
        class ResourceSomething {
        }

        @Path("/api/widgets/{first : .*}b{last : .*}")
        class ResourceAnother {
        }

        ResourceClass resourceSomething = fromObject(new ResourceSomething());
        ResourceClass resourceAnother = fromObject(new ResourceAnother());
        RequestMatcher rm = new RequestMatcher(set(resourceSomething, resourceAnother));

        URI uri = URI.create("api/widgets/b");
        assertThat(resourceSomething.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(resourceSomething.pathPattern.numberOfLiterals, equalTo(resourceAnother.pathPattern.numberOfLiterals));
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri).candidates,
            contains(resourceAnother));
    }

    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthAndEqualNamedGroupsThenOneWithMostNonDefaultGroupsWins() {
        @Path("/api/people/{name}/belts/{id}")
        class PeopleBelts {
        }

        @Path("/api/people/{name}/belts/{id:[A-Z]+}")
        class CapitalPeopleBelts {
        }

        ResourceClass resourcePeopleBelts = fromObject(new PeopleBelts());
        ResourceClass resourcePeopleBeltsInCapitals = fromObject(new CapitalPeopleBelts());
        RequestMatcher rm = new RequestMatcher(set(resourcePeopleBelts, resourcePeopleBeltsInCapitals));

        URI uri = URI.create("api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.numberOfLiterals, equalTo(resourcePeopleBeltsInCapitals.pathPattern.numberOfLiterals));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri).candidates,
            contains(resourcePeopleBeltsInCapitals));
    }

    @Test
    public void multipleMatchesOnlyOccurWhenURLsAreTheSameExceptForVariableNames() {
        @Path("/api/people/{name}/belts/{another:[A-Z]+}")
        class PeopleBelts {
        }

        @Path("/api/people/{name}/belts/{id:[A-Z]+}")
        class CapitalPeopleBelts {
        }

        ResourceClass resourcePeopleBelts = fromObject(new PeopleBelts());
        ResourceClass resourcePeopleBeltsInCapitals = fromObject(new CapitalPeopleBelts());
        RequestMatcher rm = new RequestMatcher(set(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah, resourcePeopleBelts, resourcePeopleBeltsInCapitals));

        URI uri = URI.create("api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.numberOfLiterals, equalTo(resourcePeopleBeltsInCapitals.pathPattern.numberOfLiterals));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri).candidates,
            containsInAnyOrder(resourcePeopleBeltsInCapitals, resourcePeopleBelts));
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

    @Test
    public void methodsWithOnlyAPathParamWork() {

        @Path("api/fruits")
        class Fruit {

            @GET
            public String getAll() {
                return "[]";
            }

            @GET
            @Path("{name}")
            public String get(@PathParam("name") String name) {
                return name;
            }
        }

        ResourceClass resourcePeopleBelts = fromObject(new Fruit());
        RequestMatcher rm = new RequestMatcher(set(resourcePeopleBelts));
//        ResourceMethod getAll = rm.findResourceMethod(Method.GET, URI.create("api/fruits"));
//        assertThat(getAll.methodHandle.getName(), equalTo("getAll"));
        RequestMatcher.MatchedMethod mm = rm.findResourceMethod(Method.GET, URI.create("api/fruits/orange"));
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathMatch.params().get("name"), equalTo("orange"));
    }

    @Test
    public void pathsOnClassesAreMatchedFirst() {
        // test example taken from https://bill.burkecentral.com/2013/05/29/the-poor-jax-rs-request-dispatching-algorithm/

        @Path("/foo")
        class Foo {
            @GET
            public String get() { return ""; }
        }
        @Path("/{s:.*}")
        class OptionsDefault {
            @OPTIONS
            public String options() { return ""; }
        }

        RequestMatcher rm = new RequestMatcher(set(fromObject(new OptionsDefault())));
        assertThat(rm.findResourceMethod(Method.OPTIONS, URI.create("foo")).resourceMethod.methodHandle.getName(), equalTo("options"));

        RequestMatcher rm2 = new RequestMatcher(set(fromObject(new Foo()), fromObject(new OptionsDefault())));
        try {
            RequestMatcher.MatchedMethod actual = rm2.findResourceMethod(Method.OPTIONS, URI.create("foo"));
            // NOTE that in this case, default OPTIONS handling should happen, but that's not supported yet so throw an exception instead
            Assert.fail("Should not have gotten a value, but got " + actual);
        } catch (NotAllowedException e) {
            assertThat(e.getMessage(), equalTo("HTTP 405 Method Not Allowed"));
        }

    }

    @Test
    public void matchedMethodsMergePathParamsFromResourceClass() {
        @Path("api/{fruitFamily}")
        class Fruit {
            @GET
            @Path("{fruitType}")
            public String get() { return ""; }
        }

        RequestMatcher rm = new RequestMatcher(set(fromObject(new Fruit())));
        RequestMatcher.MatchedMethod mm = rm.findResourceMethod(Method.GET, URI.create("api/citrus/orange"));
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        Map<String, String> pathParams = mm.pathMatch.params();
        assertThat(pathParams.get("fruitType"), equalTo("orange"));
        assertThat(pathParams.get("fruitFamily"), equalTo("citrus"));
    }


    private static Set<ResourceClass> set(ResourceClass... restResources) {
        return Stream.of(restResources).collect(Collectors.toSet());
    }

}