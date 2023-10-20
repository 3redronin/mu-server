package io.muserver.rest;

import io.muserver.Cookie;
import io.muserver.Headers;
import io.muserver.HeadersFactory;
import io.muserver.Method;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import scaffolding.NotImplementedMuRequest;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestMatcherTest {
    private final SchemaObjectCustomizer customizer = new CompositeSchemaObjectCustomizer(emptyList());

    private final List<ParamConverterProvider> paramConverterProviders = ResourceMethodParamTest.BUILT_IN_PARAM_PROVIDERS;
    private final ResourceClass resourceOne = ResourceClass.fromObject(new ResourceOne(), paramConverterProviders, customizer);
    private final ResourceClass resourceOneV2 = ResourceClass.fromObject(new ResourceOneV2(), paramConverterProviders, customizer);
    private final ResourceClass resourceSomething = ResourceClass.fromObject(new ResourceSomething(), paramConverterProviders, customizer);
    private final ResourceClass resourceSomethingYeah = ResourceClass.fromObject(new ResourceSomethingYeah(), paramConverterProviders, customizer);
    private final ResourceClass resourceAnother = ResourceClass.fromObject(new ResourceAnother(), paramConverterProviders, customizer);
    private final RequestMatcher rm = new RequestMatcher(asList(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah));

    @Test
    public void throwsIfNoValidCandidates() {
        assertThrows(NotMatchedException.class, () -> stepOneMatches(URI.create("api/three"), rm));
    }

    @Test
    public void findsTheMostSpecificInstanceAvailable() throws NotMatchedException {
        assertThat(stepOneMatches(URI.create("api/resources/one/2"), rm),
            contains(resourceOneV2));
    }

    @Test
    public void ifMultipleMatchesThenTheLongestPathRegexWins() throws NotMatchedException {
        URI uri = URI.create("api/widgets/something-else-yeah");
        assertThat(resourceSomething.matches(uri), is(false));
        assertThat(resourceSomethingYeah.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(stepOneMatches(uri, rm),
            contains(resourceSomethingYeah));
    }

    private List<ResourceClass> stepOneMatches(URI uri, RequestMatcher rm1) throws NotMatchedException {
        return rm1.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest(uri.toString()).candidates
            .stream().map(rm -> rm.resourceClass).collect(toList());
    }

    @Test
    public void ifJustThePrefixMatchesThenItDoesNotMatchIfThereAreNoSubResourceMethods() throws NotMatchedException {
        assertThrows(NotMatchedException.class, () -> rm.stepOneIdentifyASetOfCandidateRootResourceClassesMatchingTheRequest("/api/widgets/something-else-yeah/uhuh"));
    }

    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthThenMostNamedGroupsWins() throws NotMatchedException {

        @Path("/api/widgets/b")
        class ResourceSomething {
        }

        @Path("/api/widgets/{first : .*}b{last : .*}")
        class ResourceAnother {
        }

        ResourceClass resourceSomething = ResourceClass.fromObject(new ResourceSomething(), paramConverterProviders, customizer);
        ResourceClass resourceAnother = ResourceClass.fromObject(new ResourceAnother(), paramConverterProviders, customizer);
        RequestMatcher rm = new RequestMatcher(asList(resourceSomething, resourceAnother));

        URI uri = URI.create("api/widgets/b");
        assertThat(resourceSomething.matches(uri), is(true));
        assertThat(resourceAnother.matches(uri), is(true));
        assertThat(resourceSomething.pathPattern.numberOfLiterals, equalTo(resourceAnother.pathPattern.numberOfLiterals));
        assertThat(stepOneMatches(uri, rm), contains(resourceAnother));
    }

    @Test
    public void ifMultipleMatchesWithEqualPathRegexLengthAndEqualNamedGroupsThenOneWithMostNonDefaultGroupsWins() throws NotMatchedException {
        @Path("/api/people/{name}/belts/{id}")
        class PeopleBelts {
        }

        @Path("/api/people/{name}/belts/{id:[A-Z]+}")
        class CapitalPeopleBelts {
        }

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new PeopleBelts(), paramConverterProviders, customizer);
        ResourceClass resourcePeopleBeltsInCapitals = ResourceClass.fromObject(new CapitalPeopleBelts(), paramConverterProviders, customizer);
        RequestMatcher rm = new RequestMatcher(asList(resourcePeopleBelts, resourcePeopleBeltsInCapitals));

        URI uri = URI.create("api/people/dan/belts/COLBELT");
        assertThat(resourcePeopleBelts.matches(uri), is(true));
        assertThat(resourcePeopleBeltsInCapitals.matches(uri), is(true));
        assertThat(resourcePeopleBelts.pathPattern.numberOfLiterals, equalTo(resourcePeopleBeltsInCapitals.pathPattern.numberOfLiterals));
        assertThat(resourcePeopleBelts.pathPattern.namedGroups().size(), equalTo(resourcePeopleBeltsInCapitals.pathPattern.namedGroups().size()));
        assertThat(stepOneMatches(uri, rm),
            contains(resourcePeopleBeltsInCapitals));
    }

    @Test
    public void multipleMatchesOnlyOccurWhenURLsAreTheSameExceptForVariableNames() throws NotMatchedException {
        @Path("/api/people/{name}/belts/{another:[A-Z]+}")
        class PeopleBelts {
        }

        @Path("/api/people/{name}/belts/{id:[A-Z]+}")
        class CapitalPeopleBelts {
        }

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new PeopleBelts(), paramConverterProviders, customizer);
        ResourceClass resourcePeopleBeltsInCapitals = ResourceClass.fromObject(new CapitalPeopleBelts(), paramConverterProviders, customizer);
        RequestMatcher rm = new RequestMatcher(asList(resourceOne, resourceOneV2, resourceSomething, resourceAnother, resourceSomethingYeah, resourcePeopleBelts, resourcePeopleBeltsInCapitals));

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
    public void methodsWithOnlyAPathParamWork() throws NotMatchedException {

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

        ResourceClass resourcePeopleBelts = ResourceClass.fromObject(new Fruit(), paramConverterProviders, customizer);
        RequestMatcher rm = new RequestMatcher(singletonList(resourcePeopleBelts));
        ResourceMethod getAll = findResourceMethod(rm, Method.GET, "api/fruits", emptyList(), null).resourceMethod;
        assertThat(getAll.methodHandle.getName(), equalTo("getAll"));
        RequestMatcher.MatchedMethod mm = findResourceMethod(rm, Method.GET, "api/fruits/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("name").getPath(), equalTo("orange"));
    }

    @Test
    public void pathsOnClassesAreMatchedFirst() throws NotMatchedException {
        // test example taken from https://bill.burkecentral.com/2013/05/29/the-poor-jax-rs-request-dispatching-algorithm/

        @Path("/foo")
        class Foo {
            @GET
            public String get() {
                return "";
            }
        }
        @Path("/{s:.*}")
        class OptionsDefault {
            @OPTIONS
            public String options() {
                return "";
            }
        }

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new OptionsDefault(), paramConverterProviders, customizer)));
        assertThat(findResourceMethod(rm, Method.OPTIONS, "foo", emptyList(), null).resourceMethod.methodHandle.getName(), equalTo("options"));

        RequestMatcher rm2 = new RequestMatcher(asList(ResourceClass.fromObject(new Foo(), paramConverterProviders, customizer), ResourceClass.fromObject(new OptionsDefault(), paramConverterProviders, customizer)));
        try {
            RequestMatcher.MatchedMethod actual = findResourceMethod(rm2, Method.OPTIONS, "foo", emptyList(), null);
            // NOTE that in this case, default OPTIONS handling should happen, but that's not supported yet so throw an exception instead
            Assert.fail("Should not have gotten a value, but got " + actual);
        } catch (NotAllowedException e) {
            assertThat(e.getMessage(), equalTo("HTTP 405 Method Not Allowed"));
        }

    }

    @Test
    public void matchedMethodsMergePathParamsFromResourceClass() throws NotMatchedException {
        @Path("api/{fruitFamily}")
        class Fruit {
            @GET
            @Path("{fruitType}")
            public String get() {
                return "";
            }
        }

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new Fruit(), paramConverterProviders, customizer)));
        RequestMatcher.MatchedMethod mm = findResourceMethod(rm, Method.GET, "api/citrus/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("fruitType").getPath(), equalTo("orange"));
        assertThat(mm.pathParams.get("fruitFamily").getPath(), equalTo("citrus"));
    }


    @Path("api/{fruitFamily}")
    interface FruitInterface {

        @GET
        @Path("{fruitType}")
        String get();
    }

    @Test
    public void paramsCanBeDefinedOnTheInterface() throws NotMatchedException {
        class FruitImpl implements FruitInterface {
            public String get() {
                return "";
            }
        }

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new FruitImpl(), paramConverterProviders, customizer)));
        RequestMatcher.MatchedMethod mm = findResourceMethod(rm, Method.GET, "api/citrus/orange", emptyList(), null);
        assertThat(mm.resourceMethod.methodHandle.getName(), equalTo("get"));
        assertThat(mm.pathParams.get("fruitType").getPath(), equalTo("orange"));
        assertThat(mm.pathParams.get("fruitFamily").getPath(), equalTo("citrus"));
    }

    @Test
    public void acceptHeadersAreUsedForMethodMatching() throws NotMatchedException {
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

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new PictureThat(), paramConverterProviders, customizer)));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("image/gif")), null), equalTo("image"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("image/jpeg")), null), equalTo("image"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("image/png")), null), equalTo("image"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("application/json")), null), equalTo("json"));
        assertThat(nameOf(rm, emptyList(), null), equalTo("json"));
        assertNotAcceptable(rm, singletonList(MediaType.valueOf("image/bmp")), null);
    }

    @Test
    public void defaultAcceptHeadersCanBeSpecifiedAtTheClassLevel() throws NotMatchedException {
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

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new PictureThat(), paramConverterProviders, customizer)));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("text/plain")), null), equalTo("text"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("text/plain;q=1")), null), equalTo("text"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("text/*")), null), equalTo("text"));
        assertThat(nameOf(rm, singletonList(MediaType.valueOf("application/json")), null), equalTo("json"));

        assertNotAcceptable(rm, singletonList(MediaType.valueOf("image/*")), null);
    }

    @Test
    public void consumesCanBeUsedToMatchMethods() throws NotMatchedException {
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

        RequestMatcher rm = new RequestMatcher(singletonList(ResourceClass.fromObject(new PictureThat(), paramConverterProviders, customizer)));
        assertThat(nameOf(rm, emptyList(), "text/plain"), equalTo("text"));
        assertThat(nameOf(rm, emptyList(), null), equalTo("json"));

        assertNotAcceptable(rm, singletonList(MediaType.valueOf("text/plain")), "application/json");
    }

    private static String nameOf(RequestMatcher rm, List<MediaType> acceptHeaders, String requestBodyContentType) throws NotMatchedException {
        return findResourceMethod(rm, Method.GET, "pictures", acceptHeaders, requestBodyContentType).resourceMethod.methodHandle.getName();
    }

    private static void assertNotAcceptable(RequestMatcher rm, List<MediaType> acceptHeaders, String requestBodyContentType) {
        try {
            RequestMatcher.MatchedMethod found = findResourceMethod(rm, Method.GET, "pictures", acceptHeaders, requestBodyContentType);
            Assert.fail("Should have thrown exception but instead got " + found);
        } catch (NotAcceptableException e) {
            assertThat(e.getMessage(), equalTo("HTTP 406 Not Acceptable"));
        } catch (Exception ex) {
            Assert.fail("Should not throw this type of exception: " + ex);
        }
    }

    private static RequestMatcher.MatchedMethod findResourceMethod(RequestMatcher rm, Method method, String path, List<MediaType> acceptHeaders, String contentBodyType) throws NotMatchedException {
        NotImplementedMuRequest request = new NotImplementedMuRequest() {
            @Override
            public URI uri() {
                return URI.create("http://localhost/").resolve(path);
            }

            @Override
            public Method method() {
                return method;
            }

            @Override
            public String contextPath() {
                return "/";
            }

            @Override
            public Headers headers() {
                Map<String, Object> entries = new HashMap<>();
                if (contentBodyType != null) {
                    entries.put(HttpHeaderNames.CONTENT_TYPE.toString(), contentBodyType);
                }
                return HeadersFactory.create(entries);
            }

            @Override
            public List<Cookie> cookies() {
                return emptyList();
            }
        };
        InputStream inputStream = contentBodyType == null ? null : new ByteArrayInputStream("Hello".getBytes(StandardCharsets.US_ASCII));
        JaxRSRequest rc = new JaxRSRequest(request, null, inputStream, request.uri().getPath(), null, emptyList(), null);
        return rm.findResourceMethod(rc, method, acceptHeaders, null);
    }


}