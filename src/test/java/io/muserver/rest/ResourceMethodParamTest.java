package io.muserver.rest;

import io.muserver.MuException;
import io.muserver.MuServer;
import io.muserver.Mutils;
import jakarta.ws.rs.*;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class ResourceMethodParamTest {


    public static final List<ParamConverterProvider> BUILT_IN_PARAM_PROVIDERS = Collections.singletonList(new BuiltInParamConverterProvider());
    private MuServer server;

    @Test
    public void canFindStuffOut() {

        @SuppressWarnings("unused")
        class Sample {
            public void defaultAndEncoded(@QueryParam("dummy1") @DefaultValue("A Default") @Encoded String defaultAndEncoded,
                                          @MatrixParam("dummy2") @DefaultValue("Another Default") String defaultAndNotEncoded,
                                          @FormParam("dummy3") @Encoded String noDefaultButEncoded,
                                          @HeaderParam("dummy4") String noDefaultAndNoEncoded,
                                          String messageBasedParam) {
            }
        }

        AtomicInteger indexer = new AtomicInteger();
        List<ResourceMethodParam> params = Stream.of(Sample.class.getDeclaredMethods()[0].getParameters())
            .map(p -> ResourceMethodParam.fromParameter(indexer.getAndIncrement(), p, BUILT_IN_PARAM_PROVIDERS, null))
            .collect(Collectors.toList());

        ResourceMethodParam.RequestBasedParam defaultAndEncoded = (ResourceMethodParam.RequestBasedParam) params.get(0);
        assertThat(defaultAndEncoded.defaultValue(), equalTo("A Default"));
        assertThat(defaultAndEncoded.encodedRequested, is(true));
        assertThat(defaultAndEncoded.key, equalTo("dummy1"));

        ResourceMethodParam.RequestBasedParam defaultAndNotEncoded = (ResourceMethodParam.RequestBasedParam) params.get(1);
        assertThat(defaultAndNotEncoded.defaultValue(), equalTo("Another Default"));
        assertThat(defaultAndNotEncoded.encodedRequested, is(false));
        assertThat(defaultAndNotEncoded.key, equalTo("dummy2"));

        ResourceMethodParam.RequestBasedParam noDefaultButEncoded = (ResourceMethodParam.RequestBasedParam) params.get(2);
        assertThat(noDefaultButEncoded.defaultValue(), is(nullValue()));
        assertThat(noDefaultButEncoded.encodedRequested, is(true));
        assertThat(noDefaultButEncoded.key, equalTo("dummy3"));

        ResourceMethodParam.RequestBasedParam noDefaultAndNoEncoded = (ResourceMethodParam.RequestBasedParam) params.get(3);
        assertThat(noDefaultAndNoEncoded.defaultValue(), is(nullValue()));
        assertThat(noDefaultAndNoEncoded.encodedRequested, is(false));
        assertThat(noDefaultAndNoEncoded.key, equalTo("dummy4"));

        assertThat(params.get(4), instanceOf(ResourceMethodParam.MessageBodyParam.class));
    }

    @Test
    public void canGetStuffFromQueryString() throws IOException {

        @Path("samples")
        class Sample {
            @GET
            public String getIt(@QueryParam("one") String one,
                                @QueryParam("two") @DefaultValue("Some default") String two,
                                @QueryParam("three") @Encoded String three
            ) {
                return one + " / " + two + " / " + three;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?one=some%20thing%2F&one=ignored&three=some%20thing%2F").toString()))) {
            assertThat(resp.body().string(), equalTo("some thing/ / Some default / some%20thing%2F"));
        }
    }


    @Test
    public void canConvertPrimitives() throws IOException {
        @Path("samples")
        class Sample {
            @GET
            public String getIt(
                @QueryParam("bitey") byte bitey,
                @QueryParam("shorty") short shorty,
                @QueryParam("inty") int inty,
                @QueryParam("long") long davidLongy,
                @QueryParam("floater") float floater,
                @QueryParam("doubleedoo") double doubleedoo,
                @QueryParam("charred") char charred,
                @QueryParam("boolyeah") boolean boolyeah) {
                return bitey + " / " + shorty + " / " + inty + " / " + davidLongy + " / " + floater + " / " + doubleedoo + " / " + (charred == 0 ? '0' : charred) + " / " + boolyeah;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?bitey=127&shorty=1&inty=-10&long=2183748372&floater=123.34&doubleedoo=8753.1234&charred=C&boolyeah=true").toString()))) {
            assertThat(resp.body().string(), equalTo("127 / 1 / -10 / 2183748372 / 123.34 / 8753.1234 / C / true"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.body().string(), equalTo("0 / 0 / 0 / 0 / 0.0 / 0.0 / 0 / false"));
        }
    }


    @Test
    public void primitiveDefaultsCanBeSpecified() throws IOException {
        @Path("samples")
        class Sample {
            @GET
            public String getIt(
                @DefaultValue("1") @QueryParam("bitey") byte bitey,
                @DefaultValue("2") @QueryParam("shorty") short shorty,
                @DefaultValue("3") @QueryParam("inty") int inty,
                @DefaultValue("4") @QueryParam("long") long davidLongy,
                @DefaultValue("5.5") @QueryParam("floater") float floater,
                @DefaultValue("6.6") @QueryParam("doubleedoo") double doubleedoo,
                @DefaultValue("d") @QueryParam("charred") char charred,
                @DefaultValue("true") @QueryParam("boolyeah") boolean boolyeah) {
                return bitey + " / " + shorty + " / " + inty + " / " + davidLongy + " / " + floater + " / " + doubleedoo + " / " + (charred == 0 ? '0' : charred) + " / " + boolyeah;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?bitey=127&shorty=1&inty=-10&long=2183748372&floater=123.34&doubleedoo=8753.1234&charred=C&boolyeah=true").toString()))) {
            assertThat(resp.body().string(), equalTo("127 / 1 / -10 / 2183748372 / 123.34 / 8753.1234 / C / true"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.body().string(), equalTo("1 / 2 / 3 / 4 / 5.5 / 6.6 / d / true"));
        }
    }

    @Test
    public void canConvertBoxed() throws IOException {

        @Path("samples")
        class Sample {
            @GET
            public String getIt(
                @QueryParam("bitey") Byte bitey,
                @QueryParam("shorty") Short shorty,
                @QueryParam("inty") Integer inty,
                @QueryParam("long") Long davidLongy,
                @QueryParam("floater") Float floater,
                @QueryParam("doubleedoo") Double doubleedoo,
                @QueryParam("charred") Character charred,
                @QueryParam("boolyeah") Boolean boolyeah) {
                return bitey + " / " + shorty + " / " + inty + " / " + davidLongy + " / " + floater + " / " + doubleedoo + " / " + charred + " / " + boolyeah;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?bitey=127&shorty=1&inty=-10&long=2183748372&floater=123.34&doubleedoo=8753.1234&charred=C&boolyeah=true").toString()))) {
            assertThat(resp.body().string(), equalTo("127 / 1 / -10 / 2183748372 / 123.34 / 8753.1234 / C / true"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.body().string(), equalTo("null / null / null / null / null / null / null / null"));
        }

    }


    @Test
    public void boxedPrimitiveDefaultsCanBeSpecified() throws IOException {
        @Path("samples")
        class Sample {
            @GET
            public String getIt(
                @DefaultValue("1") @QueryParam("bitey") Byte bitey,
                @DefaultValue("2") @QueryParam("shorty") Short shorty,
                @DefaultValue("3") @QueryParam("inty") Integer inty,
                @DefaultValue("4") @QueryParam("long") Long davidLongy,
                @DefaultValue("5.5") @QueryParam("floater") Float floater,
                @DefaultValue("6.6") @QueryParam("doubleedoo") Double doubleedoo,
                @DefaultValue("d") @QueryParam("charred") Character charred,
                @DefaultValue("true") @QueryParam("boolyeah") Boolean boolyeah) {
                return bitey + " / " + shorty + " / " + inty + " / " + davidLongy + " / " + floater + " / " + doubleedoo + " / " + (charred == 0 ? '0' : charred) + " / " + boolyeah;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?bitey=127&shorty=1&inty=-10&long=2183748372&floater=123.34&doubleedoo=8753.1234&charred=C&boolyeah=true").toString()))) {
            assertThat(resp.body().string(), equalTo("127 / 1 / -10 / 2183748372 / 123.34 / 8753.1234 / C / true"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.body().string(), equalTo("1 / 2 / 3 / 4 / 5.5 / 6.6 / d / true"));
        }
    }

    @Test
    public void canGetStuffFromTheForm() throws IOException {
        @Path("samples")
        class Sample {
            @POST
            public String postIt(
                @FormParam("someThing") String something,
                @FormParam("someThing2") @DefaultValue("Ah hah") String something2,
                @FormParam("someThing3") String something3,
                @FormParam("someThings") List<String> somethings) {
                return something + " / " + something2 + " / " + something3 + " / " + somethings;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString())
            .post(
                new FormBody.Builder()
                    .add("someThing", "Is here")
                    .add("someThing", "Is ignored")
                    .add("someThings", "some things one")
                    .add("someThings", "some things two")
                    .build())
        )) {
            assertThat(resp.body().string(), equalTo("Is here / Ah hah / null / [some things one, some things two]"));
        }
    }

    @SuppressWarnings("unused")
    enum Breed {
        CHIHUAHUA, BIG_HAIRY, YELPER;
        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    @Test
    public void errorMessagesAreNiceForNormalTypes() {
        class Something {}
        try {
            @Path("samples") class Sample {
                @GET public void getIt(@QueryParam("something") Something something) { }
            }
            restHandler(new Sample()).build();
            Assertions.fail("Should have thrown");
        } catch (MuException e) {
            assertThat(e.getMessage(), startsWith("Could not find a suitable ParamConverter for class " + Something.class.getName()));
        }
    }

    @Test
    public void errorMessagesAreNiceForGenericTypes() {
        class Something {}
        try {
            @Path("samples") class Sample {
                @GET public void getIt(@QueryParam("something") List<Something> something) { }
            }
            restHandler(new Sample()).build();
            Assertions.fail("Should have thrown");
        } catch (MuException e) {
            assertThat(e.getMessage(), startsWith("Could not find a suitable ParamConverter for java.util.List<" + Something.class.getName() + ">"));
        }
    }

    @Test
    public void errorMessagesAreNiceForUnboundTypes() {
        try {
            @Path("samples") class Sample {
                @GET public void getIt(@QueryParam("something") List<?> something) { }
            }
            restHandler(new Sample()).build();
            Assertions.fail("Should have thrown");
        } catch (MuException e) {
            assertThat(e.getMessage(), startsWith("Could not find a suitable ParamConverter for java.util.List<?>"));
        }
    }

    @Test
    public void enumsCanBeUsed() throws IOException {
        @Path("samples")
        @Produces("text/plain")
        class Sample {
            @GET
            public String getIt(
                @QueryParam("breedOne") Breed breed1,
                @QueryParam("breedTwo") Breed breed2,
                @DefaultValue("BIG_HAIRY") @QueryParam("breedThree") Breed breed3) {
                return breed1 + " / " + breed2 + " / " + breed3;
            }

            @GET
            @Path("headers")
            public String getItFromHeaders(
                @HeaderParam("breedOne") Breed breed1,
                @HeaderParam("breedTwo") Breed breed2,
                @DefaultValue("BIG_HAIRY") @HeaderParam("breedThree") Breed breed3) {
                return breed1 + " / " + breed2 + " / " + breed3;
            }

            @GET
            @Path("multiple")
            public String getMultiple(@QueryParam("breeds") List<Breed> breeds) {
                if (breeds == null) return "null";
                if (breeds.isEmpty()) return "empty list";
                return breeds.stream().map(breed -> breed == null ? "nullinlist" : breed.name()).collect(Collectors.joining(","));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample()).withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)).start();
        try (Response resp = call(request()
            .url(server.uri().resolve("/samples?breedOne=CHIHUAHUA&breedOne=ignored").toString()))) {
            assertThat(resp.body().string(), equalTo("chihuahua / null / big_hairy"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/headers").toString())
            .header("breedOne", Breed.CHIHUAHUA.name()))) {
            assertThat(resp.body().string(), equalTo("chihuahua / null / big_hairy"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?breedOne=BAD_DOG").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), startsWith("<h1>400 Bad Request</h1><p>Could not convert String value &quot;BAD_DOG&quot; to a"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/samples/multiple?breeds=CHIHUAHUA&breeds=YELPER").toString()))) {
            assertThat(resp.body().string(), equalTo("CHIHUAHUA,YELPER"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/multiple?breeds=").toString()))) {
//            assertThat(resp.body().string(), equalTo("empty list"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/multiple").toString()))) {
            assertThat(resp.body().string(), equalTo("empty list"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/multiple?breeds=CHIHUAHUA&breeds=INVALID&breeds=YELPER").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString("Could not convert"));
        }
    }

    @Test
    public void wildcardEnumsCanBeUsed() throws IOException {
        @Path("samples")
        @Produces("text/plain")
        class Sample {
            @GET
            public String getIt(
                @QueryParam("breeds") List<? extends Breed> breeds) {
                return breeds.stream().map(Enum::name).collect(Collectors.joining(", "));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Sample()).withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM).withOpenApiJsonUrl("/openapi.json")).start();
        try (Response resp = call(request(server.uri().resolve("/samples?breeds=CHIHUAHUA&breeds=YELPER")))) {
            assertThat(resp.body().string(), equalTo("CHIHUAHUA, YELPER"));
        }
        try (Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject api = new JSONObject(resp.body().string());
            JSONObject param = (JSONObject) api.query("/paths/~1samples/get/parameters/0/schema");
            assertThat(param.getString("type"), is("array"));
            JSONObject items = param.getJSONObject("items");
            assertThat(items.opt("nullable"), is(nullValue()));
            assertThat(items.getString("type"), is("string"));
            assertThat(items.getJSONArray("enum"), containsInAnyOrder(Stream.of(Breed.values()).map(Breed::name).toArray()));
        }
    }


    @SuppressWarnings("unused")
    public static class DogWithValueOf {
        final String name;
        final String breed;

        DogWithValueOf(String value) {
            throw new RuntimeException("Not called because not public");
        }

        private DogWithValueOf(String name, String breed) {
            this.name = name;
            this.breed = breed;
        }

        public static DogWithValueOf fromString(String dummy) {
            throw new RuntimeException("valueOf should be preferred");
        }

        public static DogWithValueOf valueOf(String value) {
            return new DogWithValueOf(value.split(",")[0], value.split(",")[1]);
        }

        public String toString() {
            return this.breed + " - " + this.name;
        }
    }

    @Test
    public void staticValueOfMethodCanBeUsed() throws IOException {
        @Path("dogs")
        class Dogs {
            @GET
            public String getIt(
                @QueryParam("dogValue") DogWithValueOf dog,
                @QueryParam("noDawg") DogWithValueOf dog2,
                @DefaultValue("Pinkle,Twinkle") @QueryParam("defaultDog") DogWithValueOf dog3) {
                return dog + " / " + dog2 + " / " + dog3;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Dogs())).start();
        try (Response resp = call(request().url(server.uri().resolve("/dogs?dogValue=Little,Chihuahua").toString()))) {
            assertThat(resp.body().string(), equalTo("Chihuahua - Little / null / Twinkle - Pinkle"));
        }
    }


    @SuppressWarnings("unused")
    public static class DogWithFromString {
        final String name;
        final String breed;

        DogWithFromString(String value) {
            throw new RuntimeException("Not called because not public");
        }

        private DogWithFromString(String name, String breed) {
            this.name = name;
            this.breed = breed;
        }

        public static DogWithFromString fromString(String value) {
            return new DogWithFromString(value.split(",")[0], value.split(",")[1]);
        }

        public String valueOf(String dummy) {
            throw new RuntimeException("This should not be called because it does not return the correct type");
        }

        public String toString() {
            return this.breed + " - " + this.name;
        }
    }

    @Test
    public void staticFromStringMethodCanBeUsed() throws IOException {
        @Path("dogs")
        class Dogs {
            @GET
            public String getIt(
                @QueryParam("dogValue") DogWithFromString dog,
                @QueryParam("noDawg") DogWithFromString dog2,
                @DefaultValue("Pinkle,Twinkle") @QueryParam("defaultDog") DogWithFromString dog3) {
                return dog + " / " + dog2 + " / " + dog3;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Dogs())).start();
        try (Response resp = call(request().url(server.uri().resolve("/dogs?dogValue=Little,Chihuahua").toString()))) {
            assertThat(resp.body().string(), equalTo("Chihuahua - Little / null / Twinkle - Pinkle"));
        }
    }


    @SuppressWarnings("unused")
    public static class DogWithConstructor {
        final String name;
        final String breed;

        public DogWithConstructor(String value) {
            this.name = value.split(",")[0];
            this.breed = value.split(",")[1];
        }

        public static DogWithConstructor fromString(String dummy) {
            throw new RuntimeException("Constructor should be preferred");
        }

        public static DogWithConstructor valueOf(String value) {
            throw new RuntimeException("Constructor should be preferred");
        }

        public String toString() {
            return this.breed + " - " + this.name;
        }
    }

    @Test
    public void singleStringConstructorsWork() throws IOException {
        @Path("dogs")
        class Dogs {
            @GET
            public String getIt(
                @QueryParam("dogValue") DogWithConstructor dog,
                @QueryParam("noDawg") DogWithConstructor dog2,
                @DefaultValue("Pinkle,Twinkle") @QueryParam("defaultDog") DogWithConstructor dog3) {
                return dog + " / " + dog2 + " / " + dog3;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Dogs())).start();
        try (Response resp = call(request().url(server.uri().resolve("/dogs?dogValue=Little,Chihuahua").toString()))) {
            assertThat(resp.body().string(), equalTo("Chihuahua - Little / null / Twinkle - Pinkle"));
        }
    }


    static class Cat implements Comparable<Cat> {
        final String name;

        public Cat(String name) {
            this.name = name;
        }

        @Override
        public int compareTo(Cat o) {
            return name.compareTo(o.name);
        }
    }

    @Test
    public void collectionsWork() throws IOException {
        @Path("cats")
        class Cats {
            @GET
            @Path("list")
            public String getList(
                @QueryParam("cats") List<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d == null ? "null in list" : d.name).collect(Collectors.joining(", "));
            }

            @GET
            @Path("set")
            public String getSet(
                @QueryParam("cats") Set<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d == null ? "null in set" : d.name).sorted().collect(Collectors.joining(", "));
            }

            @GET
            @Path("sortedSet")
            public String getSortedSet(
                @QueryParam("cats") SortedSet<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d == null ? "null in sorted set" : d.name).collect(Collectors.joining(", "));
            }

            @GET
            @Path("collection")
            public String getCollection(@QueryParam("cats") Collection<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d == null ? "null in collection" : d.name).sorted().collect(Collectors.joining(", "));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Cats()).withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)).start();

        try (Response resp = call(request().url(server.uri().resolve("/cats/list?cats=Little&cats=Twinkle").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/collection?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/cats/list?cats=").toString()))) {
            assertThat(resp.body().string(), equalTo("null in list"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set?cats=").toString()))) {
            assertThat(resp.body().string(), equalTo("null in set"));
        }
//        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet?cats=").toString()))) {
//            assertThat(resp.body().string(), equalTo("null in sorted set"));
//        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/collection?cats=").toString()))) {
            assertThat(resp.body().string(), equalTo("null in collection"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/cats/list").toString()))) {
            assertThat(resp.body().string(), equalTo("(empty)"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set").toString()))) {
            assertThat(resp.body().string(), equalTo("(empty)"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet").toString()))) {
            assertThat(resp.body().string(), equalTo("(empty)"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/collection").toString()))) {
            assertThat(resp.body().string(), equalTo("(empty)"));
        }


    }

    @Test
    public void collectionsCanHaveDefaultValues() throws IOException {
        @Path("cats")
        class Cats {
            @GET
            @Path("list")
            public String getList(
                @QueryParam("cats") @DefaultValue("name 1, name 2") List<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d.name).collect(Collectors.joining(", "));
            }

            @GET
            @Path("set")
            public String getSet(
                @QueryParam("cats") @DefaultValue("name 1, name 2") Set<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d.name).sorted().collect(Collectors.joining(", "));
            }

            @GET
            @Path("sortedSet")
            public String getSortedSet(
                @QueryParam("cats") @DefaultValue("name 1, name 2") SortedSet<Cat> cats) {
                if (cats.isEmpty()) return "(empty)";
                return cats.stream().map(d -> d.name).collect(Collectors.joining(", "));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new Cats()).withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)).start();
        try (Response resp = call(request().url(server.uri().resolve("/cats/list?cats=Little&cats=Twinkle").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }

        try (Response resp = call(request().url(server.uri().resolve("/cats/list").toString()))) {
            assertThat(resp.body().string(), equalTo("name 1, name 2"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set").toString()))) {
            assertThat(resp.body().string(), equalTo("name 1, name 2"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet").toString()))) {
            assertThat(resp.body().string(), equalTo("name 1, name 2"));
        }
    }

    @Test
    public void customProvidersCanBeSpecified() throws IOException {
        class Tail {
            final int lengthInCM;
            final boolean isFuzzy;

            Tail(int lengthInCM, boolean isFuzzy) {
                this.lengthInCM = lengthInCM;
                this.isFuzzy = isFuzzy;
            }
        }
        @Path("tails")
        class Dogs {
            @GET
            @Path("{tail}")
            public String getIt(@PathParam("tail") Tail tail) {
                return tail.lengthInCM + "cm - is " + (tail.isFuzzy ? "" : "not ") + "fuzzy";
            }
        }
        ParamConverter<Tail> converter = new ParamConverter<Tail>() {
            public Tail fromString(String value) {
                String[] bits = value.split("-");
                return new Tail(Integer.parseInt(bits[0]), bits[1].equalsIgnoreCase("true"));
            }

            public String toString(Tail value) {
                return "A tail";
            }
        };

        server = httpsServerForTest()
            .addHandler(restHandler(new Dogs()).addCustomParamConverter(Tail.class, converter)).start();
        try (Response resp = call(request().url(server.uri().resolve("/tails/30-true").toString()))) {
            assertThat(resp.body().string(), equalTo("30cm - is fuzzy"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/tails/3-false").toString()))) {
            assertThat(resp.body().string(), equalTo("3cm - is not fuzzy"));
        }
    }

    @Test
    public void paramConverterWebExceptionsGetReturnedToClient() throws IOException {
        class Tail {
        }
        @Path("tails")
        class Dogs {
            @GET
            @Path("{tail}")
            public String getIt(@PathParam("tail") Tail tail) {
                return "not called";
            }
        }
        ParamConverter<Tail> converter = new ParamConverter<Tail>() {
            public Tail fromString(String value) {
                throw new ClientErrorException("A message huh", 402);
            }
            public String toString(Tail value) {
                return "A tail";
            }
        };

        server = httpsServerForTest()
            .addHandler(restHandler(new Dogs()).addCustomParamConverter(Tail.class, converter)).start();
        try (Response resp = call(request().url(server.uri().resolve("/tails/30-true").toString()))) {
            assertThat(resp.code(), equalTo(402));
            assertThat(resp.body().string(), containsString("A message huh"));
        }
    }


    @Test
    public void instantParamsAreAllowed() throws IOException {
        @Path("/time")
        class TimeResource {
            @GET
            public Instant get(@QueryParam("value") Instant value) {
                return value;
            }
            @POST
            public Instant post(Instant value) {
                return value;
            }

        }
        server = httpsServerForTest().addHandler(restHandler(new TimeResource())).start();
        Instant now = Instant.now();
        try (Response resp = call(request(server.uri().resolve("/time?value=" + now)))) {
            assertThat(resp.body().string(), equalTo(now.toString()));
            assertThat(resp.header("content-type"), equalTo("text/plain;charset=utf-8"));
        }
        try (Response resp = call(request(server.uri().resolve("/time")).post(RequestBody.create(now.toString(), MediaType.get("text/plain"))))) {
            assertThat(resp.body().string(), equalTo(now.toString()));
            assertThat(resp.header("content-type"), equalTo("text/plain;charset=utf-8"));
        }

        try (Response resp = call(request(server.uri().resolve("/time")))) {
            assertThat(resp.body().string(), equalTo(""));
        }
        try (Response resp = call(request(server.uri().resolve("/time?value=invalid-date")))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString(Mutils.htmlEncode("Could not convert String value \"invalid-date\" to a class java.time.Instant using public static java.time.Instant java.time.Instant.parse(java.lang.CharSequence)")));
        }
    }

    @Test
    public void localDateParamsAllowed() throws IOException {
        @Path("/time")
        class TimeResource {
            @GET
            public LocalDate get(@QueryParam("value") LocalDate value) {
                return value;
            }
            @POST
            public LocalDate post(LocalDate value) {
                return value;
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new TimeResource())).start();
        LocalDate today = LocalDate.now();
        try (Response resp = call(request(server.uri().resolve("/time?value=" + today)))) {
            assertThat(resp.body().string(), equalTo(today.toString()));
        }
        try (Response resp = call(request(server.uri().resolve("/time")).post(RequestBody.create(today.toString(), MediaType.get("text/plain"))))) {
            assertThat(resp.body().string(), equalTo(today.toString()));
            assertThat(resp.header("content-type"), equalTo("text/plain;charset=utf-8"));
        }
        try (Response resp = call(request(server.uri().resolve("/time")))) {
            assertThat(resp.body().string(), equalTo(""));
        }
        try (Response resp = call(request(server.uri().resolve("/time?value=invalid-date")))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString(Mutils.htmlEncode("Could not convert String value \"invalid-date\" to a class java.time.LocalDate using public static java.time.LocalDate java.time.LocalDate.parse(java.lang.CharSequence)")));
        }
    }

    @Test
    public void lazyDefaultValuesAreEvaluatedWhenUsed() throws IOException {
        List<String> called = new ArrayList<>();
        class LazyThing {
            public String name;

            LazyThing(String name) {
                this.name = name;
            }
        }
        class EagerThing {
            public String name;

            EagerThing(String name) {
                this.name = name;
            }
        }
        @Path("things")
        class Things {
            @GET
            @Path("lazy")
            public void lazy(@QueryParam("thing") @DefaultValue("lazy-value") LazyThing thing) {
            }

            @GET
            @Path("eager")
            public void eager(@QueryParam("thing") @DefaultValue("eager-value") EagerThing thing) {
            }
        }

        @ParamConverter.Lazy
        class LazyThingConverter implements ParamConverter<LazyThing> {
            @Override
            public LazyThing fromString(String value) {
                called.add("Lazy " + value);
                return new LazyThing(value);
            }

            @Override
            public String toString(LazyThing value) {
                return value.name;
            }
        }

        class EagerThingConverter implements ParamConverter<EagerThing> {
            @Override
            public EagerThing fromString(String value) {
                called.add("Eager " + value);
                return new EagerThing(value);
            }

            @Override
            public String toString(EagerThing value) {
                return value.name;
            }
        }

        server = httpsServerForTest()
            .addHandler(restHandler(new Things())
                .addCustomParamConverter(LazyThing.class, new LazyThingConverter())
                .addCustomParamConverter(EagerThing.class, new EagerThingConverter())
            )
            .start();

        assertThat(called, contains("Eager eager-value"));
        try (Response ignored = call(request(server.uri().resolve("/things/eager")))) {
            assertThat(called, contains("Eager eager-value"));
        }
        try (Response ignored = call(request(server.uri().resolve("/things/lazy")))) {
            assertThat(called, contains("Eager eager-value", "Lazy lazy-value"));
        }
        try (Response ignored = call(request(server.uri().resolve("/things/lazy")))) {
            assertThat(called, contains("Eager eager-value", "Lazy lazy-value", "Lazy lazy-value"));
        }
    }



    static class Car {
        private final String model;
        Car(String model) {
            this.model = model;
        }
        public String toString() {
            return "[car: " + model + "]";
        }
    }

    @Test
    public void customTypesSupported() throws Exception {

        @Path("/cars")
        class HolderResource {
            @GET
            @Path("one")
            public String get(@QueryParam("garage") Car garage) {
                return garage == null ? "nothing" : garage.toString();
            }
            @GET
            @Path("all")
            public String all(@QueryParam("garage") List<Car> holders) {
                if (holders.isEmpty()) return "(empty list)";
                return holders.stream().map(carHolder -> carHolder == null ? "null" : carHolder.toString()).collect(Collectors.joining(", "));
            }

            @GET
            @Path("defaultOne")
            public String getOneDefault(@QueryParam("garage") @DefaultValue("Holden") Car garage) {
                return garage == null ? "nothing" : garage.toString();
            }

            @GET
            @Path("defaultAll")
            public String getAllDefault(@QueryParam("garage") @DefaultValue("Ute") List<Car> holders) {
                if (holders.isEmpty()) return "(empty list)";
                return holders.stream().map(carHolder -> carHolder == null ? "null" : carHolder.toString()).collect(Collectors.joining(", "));
            }
        }
        server = httpsServerForTest()
            .addHandler(restHandler(new HolderResource())
                .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
                .addCustomParamConverterProvider(new ParamConverterProvider() {
                    @Override
                    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                        if (rawType.equals(Car.class)) {
                            return new ParamConverter<T>() {
                                @Override
                                public T fromString(String value) {
                                    if (value.isEmpty()) return null;
                                    return (T) new Car(value);
                                }

                                @Override
                                public String toString(T value) {
                                    return value.toString();
                                }
                            };
                        }
                        return null;
                    }
                })
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/cars/one")))) {
            assertThat(resp.body().string(), equalTo("nothing"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/one?garage=blah")))) {
            assertThat(resp.body().string(), equalTo("[car: blah]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/one?garage=blah&garage=ignored")))) {
            assertThat(resp.body().string(), equalTo("[car: blah]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/all")))) {
            assertThat(resp.body().string(), equalTo("(empty list)"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/all?garage=one&garage=two")))) {
            assertThat(resp.body().string(), equalTo("[car: one], [car: two]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/all?garage=one&garage=")))) {
            assertThat(resp.body().string(), equalTo("[car: one], null"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultOne")))) {
            assertThat(resp.body().string(), equalTo("[car: Holden]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultOne?garage=")))) {
            assertThat(resp.body().string(), equalTo("nothing"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultOne?garage=Ford")))) {
            assertThat(resp.body().string(), equalTo("[car: Ford]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultAll")))) {
            assertThat(resp.body().string(), equalTo("[car: Ute]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultAll?garage=one")))) {
            assertThat(resp.body().string(), equalTo("[car: one]"));
        }
        try (Response resp = call(request(server.uri().resolve("/cars/defaultAll?garage=one&garage=two")))) {
            assertThat(resp.body().string(), equalTo("[car: one], [car: two]"));
        }
    }


    @Test
    public void customGenericTypesSupported() throws Exception {
        class Holder<T> {
            private final T something;
            Holder(T something) {
                this.something = something;
            }
            public String toString() {
                return "Holder of " + something;
            }
        }
        @Path("/holders")
        class HolderResource {
            @GET
            @Path("one")
            public String get(@QueryParam("garage") Holder<Car> garage) {
                return garage == null ? "nothing" : garage.toString();
            }
            @GET
            @Path("all")
            public String all(@QueryParam("garage") List<Holder<Car>> holders) {
                if (holders.isEmpty()) return "(empty list)";
                return holders.stream().map(carHolder -> carHolder == null ? "null" : carHolder.toString()).collect(Collectors.joining(", "));
            }

            @GET
            @Path("defaultOne")
            public String getOneDefault(@QueryParam("garage") @DefaultValue("Holden") Holder<Car> garage) {
                return garage == null ? "nothing" : garage.toString();
            }

            @GET
            @Path("defaultAll")
            public String getAllDefault(@QueryParam("garage") @DefaultValue("Ute") List<Holder<Car>> holders) {
                if (holders.isEmpty()) return "(empty list)";
                return holders.stream().map(carHolder -> carHolder == null ? "null" : carHolder.toString()).collect(Collectors.joining(", "));
            }
        }
        server = httpsServerForTest()
            .addHandler(restHandler(new HolderResource())
                .withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM)
                .addCustomParamConverterProvider(new ParamConverterProvider() {
                    @Override
                    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                        if (rawType.equals(Holder.class) && ((ParameterizedType)genericType).getActualTypeArguments()[0].equals(Car.class)) {
                            ParameterizedType pt = (ParameterizedType) genericType;
                            Class typeArg = (Class) pt.getActualTypeArguments()[0];
                            if (typeArg.equals(Car.class)) {
                                return new ParamConverter<T>() {
                                    @Override
                                    public T fromString(String value) {
                                        if (value.isEmpty()) return null;
                                        return (T) new Holder(new Car(value));
                                    }

                                    @Override
                                    public String toString(T value) {
                                        return value.toString();
                                    }
                                };
                            }
                        }
                        return null;
                    }
                })
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/holders/one")))) {
            assertThat(resp.body().string(), equalTo("nothing"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/one?garage=blah")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: blah]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/one?garage=blah&garage=ignored")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: blah]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/all")))) {
            assertThat(resp.body().string(), equalTo("(empty list)"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/all?garage=one&garage=two")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: one], Holder of [car: two]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/all?garage=one&garage=")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: one], null"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultOne")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: Holden]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultOne?garage=")))) {
            assertThat(resp.body().string(), equalTo("nothing"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultOne?garage=Ford")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: Ford]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultAll")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: Ute]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultAll?garage=one")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: one]"));
        }
        try (Response resp = call(request(server.uri().resolve("/holders/defaultAll?garage=one&garage=two")))) {
            assertThat(resp.body().string(), equalTo("Holder of [car: one], Holder of [car: two]"));
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    private @interface ChangeCase {
        String value();
    }

    @Test
    public void paramConvertsHaveAccessToTheParamAnnotations() throws Exception {
        @Path("/thing")
        class ThingResource {
            @GET
            public String get(@ChangeCase("upper") @QueryParam("one") String one, @ChangeCase("lower") @QueryParam("two") String two, @QueryParam("three") String three) {
                return one + " " + two + " " + three;
            }
        }
        server = httpsServerForTest()
            .addHandler(restHandler(new ThingResource())
                .addCustomParamConverterProvider(new ParamConverterProvider() {
                    @Override
                    public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                        if (rawType.equals(String.class)) {
                            return new ParamConverter<T>() {
                                @Override
                                public T fromString(String value) {
                                    ChangeCase changer = Arrays.stream(annotations)
                                        .filter(a -> a.annotationType().equals(ChangeCase.class))
                                        .map(a -> (ChangeCase)a)
                                        .findFirst().orElse(null);
                                    if (changer != null) {
                                        value = (changer.value().equals("upper")) ? value.toUpperCase() : changer.value().equals("lower") ? value.toLowerCase() : value;
                                    }
                                    return (T) value;
                                }
                                @Override
                                public String toString(T value) {
                                    return value.toString();
                                }
                            };
                        }
                        return null;
                    }
                })
            )
            .start();
        try (Response resp = call(request(server.uri().resolve("/thing?one=ValueOne&two=ValueTwo&three=ValueThree")))) {
            assertThat(resp.body().string(), equalTo("VALUEONE valuetwo ValueThree"));
        }
    }



    @AfterEach
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }


}