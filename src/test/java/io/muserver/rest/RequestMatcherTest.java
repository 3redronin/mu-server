package io.muserver.rest;

import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import org.junit.Assert;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ParamConverterProvider;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class RequestMatcherTest {

    private List<ParamConverterProvider> paramConverterProviders = ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS;
    private final ResourceClass resourceOne = ResourceClass.fromObject(new ResourceOne(), paramConverterProviders);
    private final ResourceClass resourceOneV2 = ResourceClass.fromObject(new ResourceOneV2(), paramConverterProviders);
    private final ResourceClass resourceSomething = ResourceClass.fromObject(new ResourceSomething(), paramConverterProviders);
    private final ResourceClass resourceSomethingYeah = ResourceClass.fromObject(new ResourceSomethingYeah(), paramConverterProviders);
    private final ResourceClass resourceAnother = ResourceClass.fromObject(new ResourceAnother(), paramConverterProviders);
    private final RequestMatcher rm = new RequestMatcher(set(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah));

    @Test(expected = NotFoundException.class)
    public void throwsIfNoValidCandidates() {
        assertThat(stepOneMatches(URI.create("api/three"), rm), empty());
    }

    @Test
    public void findsTheMostSpecificInstanceAvailable() {
        assertThat(stepOneMatches(URI.create("api/resources/one/2"), rm),
            contains(resourceOneV2));
    }

    @Test
    public void ifMultipleMatchesThenTheLongestPathRegexWins() {
        URI uri = URI.create("api/widgets/something-else-yeah");
        assertThat(resourceSomething.matches(uri), is(false));
        assertThat(resourceSomethingYeah.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(stepOneMatches(uri, rm),
            contains(resourceSomethingYeah));
    }

    private List<ResourceClass> stepOneMatches(URI uri, RequestMatcher rm1) {
        return rm1.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri.toString()).candidates
            .stream().map(rm -> rm.resourceClass).collect(toList());
    }

    @Test(expected = NotFoundException.class)
    public void ifJustThePrefixMatchesThenItDoesNotMatchIfThereAreNoSubResourceMethods() {
        rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest("/api/widgets/something-else-yeah/uhuh");
    }


    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthThenMostNamedGroupsWins() {

        @Path("/api/widgets/b")
        class ResourceSomething {
        }

        @Path("/api/widgets/{first : .*}b{last : .*}")
        class ResourceAnother {
        }

        ResourceClass resourceSomething = ResourceClass.fromObject(new ResourceSomething(), paramConverterProviders);
        ResourceClass resourceAnother = ResourceClass.fromObject(new ResourceAnother(), paramConverterProviders);
        RequestMatcher rm = new RequestMatcher(set(resourceSomething, resourceAnother));

        URI uri = URI.create("api/widgets/b");
        assertThat(resourceSomething.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(resourceSomething.pathPattern.numberOfLiterals, equalTo(resourceAnother.pathPattern.numberOfLiterals));
        assertThat(stepOneMatches(uri, rm), contains(resourceAnother));
    }

    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthAndEqualNamedGroupsThenOneWithMostNonDefaultGroupsWins() {
        @Path("/api/people/{name}/belts/{id}")
        class PeopleBelts {
        }

        @Path("/api/people/{name}/belts/{id:[A-Z]+}")
        class CapitalPeopleBelts {
        }

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new PeopleBelts(), paramConverterProviders);
        ResourceClass resourcePeopleBeltsInCapitals = ResourceClass.fromObject(new CapitalPeopleBelts(), paramConverterProviders);
        RequestMatcher rm = new RequestMatcher(set(resourcePeopleBelts, resourcePeopleBeltsInCapitals));

        URI uri = URI.create("api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.numberOfLiterals, equalTo(resourcePeopleBeltsInCapitals.pathPattern.numberOfLiterals));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(stepOneMatches(uri, rm),
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

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new PeopleBelts(), paramConverterProviders);
        ResourceClass resourcePeopleBeltsInCapitals = ResourceClass.fromObject(new CapitalPeopleBelts(), paramConverterProviders);
        RequestMatcher rm = new RequestMatcher(set(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah, resourcePeopleBelts, resourcePeopleBeltsInCapitals));

        URI uri = URI.create("api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.numberOfLiterals, equalTo(resourcePeopleBeltsInCapitals.pathPattern.numberOfLiterals));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(stepOneMatches(uri, rm),
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

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new Fruit(), paramConverterProviders);
        RequestMatcher rm = new RequestMatcher(set(resourcePeopleBelts));
        ResourceMethod getAll = rm.findResourceMethod(Method.GET, "api/fruits", emptyList(), null).resourceMethod;
        assertThat(getAll.methodHandle.getName(), equalTo("getAll"));
        RequestMatcher.MatchedMethod mm = rm.findResourceMethod(Method.GET, "api/fruits/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("name"), equalTo("orange"));
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

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new OptionsDefault(), paramConverterProviders)));
        assertThat(rm.findResourceMethod(Method.OPTIONS, "foo", emptyList(), null).resourceMethod.methodHandle.getName(), equalTo("options"));

        RequestMatcher rm2 = new RequestMatcher(set(ResourceClass.fromObject(new Foo(), paramConverterProviders), ResourceClass.fromObject(new OptionsDefault(), paramConverterProviders)));
        try {
            RequestMatcher.MatchedMethod actual = rm2.findResourceMethod(Method.OPTIONS, "foo", emptyList(), null);
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

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new Fruit(), paramConverterProviders)));
        RequestMatcher.MatchedMethod mm = rm.findResourceMethod(Method.GET, "api/citrus/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("fruitType"), equalTo("orange"));
        assertThat(mm.pathParams.get("fruitFamily"), equalTo("citrus"));
    }


    @Path("api/{fruitFamily}")
    interface FruitInterface {
        @GET
        @Path("{fruitType}")
        String get();
    }
    @Test
    public void paramsCanBeDefinedOnTheInterface() {
        class FruitImpl implements FruitInterface {
            public String get() { return ""; }
        }

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new FruitImpl(), paramConverterProviders)));
        RequestMatcher.MatchedMethod mm = rm.findResourceMethod(Method.GET, "api/citrus/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("fruitType"), equalTo("orange"));
        assertThat(mm.pathParams.get("fruitFamily"), equalTo("citrus"));
    }

    @Test
    public void acceptHeadersAreUsedForMethodMatching() {
        @Path("pictures")
        class PictureThat {

            @GET
            @Produces({"image/jpeg; qs=0.5, image/gif; qs=0.5 ", " image/png; qs=0.5"})
            public String image() {
                return ";)";
            }

            @GET
            @Produces("application/json; qs=1")
            public String json() {
                return "[]";
            }
        }

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new PictureThat(), paramConverterProviders)));
        assertThat(nameOf(rm, asList(MediaType.valueOf("image/gif")), null), equalTo("image"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("image/jpeg")), null), equalTo("image"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("image/png")), null), equalTo("image"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("application/json")), null), equalTo("json"));
        assertThat(nameOf(rm, emptyList(), null), equalTo("json"));
        assertNotAcceptable(rm, asList(MediaType.valueOf("image/bmp")), null);
    }

    @Test
    public void defaultAcceptHeadersCanBeSpecifiedAtTheClassLevel() {
        @Path("pictures")
        @Produces("application/json")
        class PictureThat {

            @GET
            @Produces("text/plain")
            public String text() {
                return "hi";
            }

            @GET
            public String json() {
                return "[]";
            }
        }

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new PictureThat(), paramConverterProviders)));
        assertThat(nameOf(rm, asList(MediaType.valueOf("text/plain")), null), equalTo("text"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("text/plain;q=1")), null), equalTo("text"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("text/*")), null), equalTo("text"));
        assertThat(nameOf(rm, asList(MediaType.valueOf("application/json")), null), equalTo("json"));

        assertNotAcceptable(rm, asList(MediaType.valueOf("image/*")), null);
    }

    @Test
    public void consumesCanBeUsedToMatchMethods() {
        @Path("pictures")
        class PictureThat {

            @GET
            @Produces("text/plain")
            @Consumes("text/plain")
            public String text() {
                return "hi";
            }

            @GET
            @Produces("text/javascript")
            public String json() {
                return "[]";
            }
        }

        RequestMatcher rm = new RequestMatcher(set(ResourceClass.fromObject(new PictureThat(), paramConverterProviders)));
        assertThat(nameOf(rm, emptyList(), "text/plain"), equalTo("text"));
        assertThat(nameOf(rm, emptyList(), null), equalTo("json"));

        assertNotAcceptable(rm, asList(MediaType.valueOf("text/plain")), "application/json");
    }

    private static String nameOf(RequestMatcher rm, List<MediaType> acceptHeaders, String requestBodyContentType) {
        return rm.findResourceMethod(Method.GET, "pictures", acceptHeaders, requestBodyContentType).resourceMethod.methodHandle.getName();
    }

    private static void assertNotAcceptable(RequestMatcher rm, List<MediaType> acceptHeaders, String requestBodyContentType) {
        try {
            RequestMatcher.MatchedMethod found = rm.findResourceMethod(Method.GET, "pictures", acceptHeaders, requestBodyContentType);
            Assert.fail("Should have thrown exception but instead got " + found);
        } catch (NotAcceptableException e) {
            assertThat(e.getMessage(), equalTo("HTTP 406 Not Acceptable"));
        } catch (Exception ex) {
            Assert.fail("Should not throw this type of exception: " + ex);
        }
    }


    private static Set<ResourceClass> set(ResourceClass... restResources) {
        return Stream.of(restResources).collect(Collectors.toSet());
    }

}