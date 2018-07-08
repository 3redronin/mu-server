package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.FormBody;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.*;
import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

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
            .map(p -> ResourceMethodParam.fromParameter(indexer.getAndIncrement(), p, BUILT_IN_PARAM_PROVIDERS))
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
        server = httpsServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?one=some%20thing%2F&three=some%20thing%2F").toString()))) {
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
        server = httpsServer().addHandler(restHandler(new Sample())).start();
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
        server = httpsServer().addHandler(restHandler(new Sample())).start();
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
        server = httpsServer().addHandler(restHandler(new Sample())).start();
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
        server = httpsServer().addHandler(restHandler(new Sample())).start();
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
            public String postIt(@FormParam("someThing") String something, @FormParam("someThing2") @DefaultValue("Ah hah") String something2) {
                return something + " / " + something2;
            }
        }
        server = httpsServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString())
            .post(new FormBody.Builder().add("someThing", "Is here").build())
        )) {
            assertThat(resp.body().string(), equalTo("Is here / Ah hah"));
        }
    }

    @SuppressWarnings("unused")
    enum Breed {
        CHIHUAHUA, BIG_HAIRY, YELPER
    }

    @Test
    public void enumsCanBeUsed() throws IOException {
        @Path("samples")
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
        }
        server = httpsServer().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request().url(server.uri().resolve("/samples?breedOne=CHIHUAHUA").toString()))) {
            assertThat(resp.body().string(), equalTo("CHIHUAHUA / null / BIG_HAIRY"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples/headers").toString())
            .header("breedOne", Breed.CHIHUAHUA.name()))) {
            assertThat(resp.body().string(), equalTo("CHIHUAHUA / null / BIG_HAIRY"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/samples?breedOne=BAD_DOG").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), startsWith("<h1>400 Bad Request</h1>Could not convert String value &quot;BAD_DOG&quot; to a"));
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
        server = httpsServer().addHandler(restHandler(new Dogs())).start();
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
        server = httpsServer().addHandler(restHandler(new Dogs())).start();
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
        server = httpsServer().addHandler(restHandler(new Dogs())).start();
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
                return cats.stream().map(d -> d.name).collect(Collectors.joining(", "));
            }

            @GET
            @Path("set")
            public String getSet(
                @QueryParam("cats") Set<Cat> cats) {
                return cats.stream().map(d -> d.name).sorted().collect(Collectors.joining(", "));
            }

            @GET
            @Path("sortedSet")
            public String getSortedSet(
                @QueryParam("cats") SortedSet<Cat> cats) {
                return cats.stream().map(d -> d.name).collect(Collectors.joining(", "));
            }
        }
        server = httpsServer().addHandler(restHandler(new Cats())).start();
        try (Response resp = call(request().url(server.uri().resolve("/cats/list?cats=Little&cats=Twinkle").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/set?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/cats/sortedSet?cats=Twinkle&cats=Little").toString()))) {
            assertThat(resp.body().string(), equalTo("Little, Twinkle"));
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

        server = httpsServer()
            .addHandler(restHandler(new Dogs()).addCustomParamConverter(Tail.class, converter)).start();
        try (Response resp = call(request().url(server.uri().resolve("/tails/30-true").toString()))) {
            assertThat(resp.body().string(), equalTo("30cm - is fuzzy"));
        }
        try (Response resp = call(request().url(server.uri().resolve("/tails/3-false").toString()))) {
            assertThat(resp.body().string(), equalTo("3cm - is not fuzzy"));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }


}