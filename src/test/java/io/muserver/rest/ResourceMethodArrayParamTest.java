package io.muserver.rest;

import io.muserver.MuException;
import io.muserver.MuServer;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import okhttp3.FormBody;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class ResourceMethodArrayParamTest {

    private MuServer server;

    private enum ArrayEnum {
        FIRST, SECOND
    }

    public static class ConstructedValue {
        private final String value;

        public ConstructedValue(String value) {
            this.value = "constructor-" + value;
        }
    }

    public static class FromStringValue {
        private final String value;

        private FromStringValue(String value) {
            this.value = value;
        }

        public static FromStringValue fromString(String value) {
            return new FromStringValue("fromString-" + value);
        }
    }

    public static class ValueOfValue {
        private final String value;

        private ValueOfValue(String value) {
            this.value = value;
        }

        public static ValueOfValue valueOf(String value) {
            return new ValueOfValue("valueOf-" + value);
        }
    }

    @Test
    public void arraysReceiveValuesFromAllSupportedParameterAnnotations() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            @Path("query")
            public String query(@QueryParam("value") String[] values) {
                return join(values);
            }

            @GET
            @Path("header")
            public String header(@HeaderParam("X-Value") String[] values) {
                return join(values);
            }

            @GET
            @Path("matrix")
            public String matrix(@MatrixParam("value") String[] values) {
                return join(values);
            }

            @POST
            @Path("form")
            public String form(@FormParam("value") String[] values) {
                return join(values);
            }

            @GET
            @Path("cookie")
            public String cookie(@CookieParam("value") String[] values) {
                return join(values);
            }

            @GET
            @Path("cookie-object")
            public String cookieObject(@CookieParam("value") jakarta.ws.rs.core.Cookie[] values) {
                return Arrays.stream(values)
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining("|"));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request(server.uri().resolve("/arrays/query?value=one&value=two")))) {
            assertThat(response.body().string(), is("one|two"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/header"))
            .addHeader("X-Value", "one")
            .addHeader("X-Value", "two"))) {
            assertThat(response.body().string(), is("one|two"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/matrix;value=one;value=two")))) {
            assertThat(response.body().string(), is("one|two"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/form"))
            .post(new FormBody.Builder().add("value", "one").add("value", "two").build()))) {
            assertThat(response.body().string(), is("one|two"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/cookie"))
            .header("Cookie", "value=one"))) {
            assertThat(response.body().string(), is("one"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/cookie-object"))
            .header("Cookie", "value=one"))) {
            assertThat(response.body().string(), is("value=one"));
        }
    }

    @Test
    public void missingValuesProduceEmptyArraysAndDefaultsProduceSingletonArrays() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @POST
            @Path("missing")
            public String missing(@QueryParam("query") String[] query,
                                  @HeaderParam("X-Value") String[] header,
                                  @MatrixParam("matrix") String[] matrix,
                                  @FormParam("form") String[] form,
                                  @CookieParam("cookie") String[] cookie) {
                return lengths(query, header, matrix, form, cookie);
            }

            @POST
            @Path("defaults")
            public String defaults(@DefaultValue("query-default") @QueryParam("query") String[] query,
                                   @DefaultValue("header-default") @HeaderParam("X-Value") String[] header,
                                   @DefaultValue("matrix-default") @MatrixParam("matrix") String[] matrix,
                                   @DefaultValue("form-default") @FormParam("form") String[] form,
                                   @DefaultValue("cookie-default") @CookieParam("cookie") jakarta.ws.rs.core.Cookie[] cookie,
                                   @DefaultValue("scalar-cookie-default") @CookieParam("scalar-cookie") jakarta.ws.rs.core.Cookie scalarCookie) {
                return join(query) + ";" + join(header) + ";" + join(matrix) + ";" + join(form) + ";"
                    + cookie[0].getName() + "=" + cookie[0].getValue() + ";"
                    + scalarCookie.getName() + "=" + scalarCookie.getValue();
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request(server.uri().resolve("/arrays/missing"))
            .post(new FormBody.Builder().build()))) {
            assertThat(response.body().string(), is("0|0|0|0|0"));
        }
        try (Response response = call(request(server.uri().resolve("/arrays/defaults"))
            .post(new FormBody.Builder().build()))) {
            assertThat(response.body().string(),
                is("query-default;header-default;matrix-default;form-default;cookie=cookie-default;"
                    + "scalar-cookie=scalar-cookie-default"));
        }
    }

    @Test
    public void encodedIsAppliedToEveryValueInUriAndFormArrays() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @POST
            @Path("encoded")
            public String encoded(@Encoded @QueryParam("query") String[] query,
                                  @Encoded @MatrixParam("matrix") String[] matrix,
                                  @Encoded @FormParam("form") String[] form) {
                return join(query) + ";" + join(matrix) + ";" + join(form);
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request()
            .url(server.uri().resolve("/arrays/encoded;matrix=one%20space%2F;matrix=two%20space%2F"
                + "?query=one%20space%2F&query=two%20space%2F").toString())
            .post(new FormBody.Builder().add("form", "one space/").add("form", "two space/").build()))) {
            assertThat(response.body().string(),
                is("one%20space%2F|two%20space%2F;"
                    + "one%20space%2F|two%20space%2F;"
                    + "one+space%2F|two+space%2F"));
        }
    }

    @Test
    public void builtInConvertersAreAppliedToArrayElements() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("number") Integer[] numbers) {
                return Arrays.stream(numbers).map(number -> Integer.toString(number * 2))
                    .collect(Collectors.joining("|"));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request(server.uri().resolve("/arrays?number=10&number=21")))) {
            assertThat(response.body().string(), is("20|42"));
        }
    }

    @Test
    public void standardObjectConversionRulesAreAppliedToArrayElements() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("enum") ArrayEnum[] enums,
                              @QueryParam("constructor") ConstructedValue[] constructed,
                              @QueryParam("fromString") FromStringValue[] fromStrings,
                              @QueryParam("valueOf") ValueOfValue[] valueOfs) {
                return Arrays.stream(enums).map(Enum::name).collect(Collectors.joining("|")) + ";"
                    + Arrays.stream(constructed).map(value -> value.value).collect(Collectors.joining("|")) + ";"
                    + Arrays.stream(fromStrings).map(value -> value.value).collect(Collectors.joining("|")) + ";"
                    + Arrays.stream(valueOfs).map(value -> value.value).collect(Collectors.joining("|"));
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request(server.uri().resolve("/arrays"
            + "?enum=FIRST&enum=SECOND"
            + "&constructor=one&constructor=two"
            + "&fromString=one&fromString=two"
            + "&valueOf=one&valueOf=two")))) {
            assertThat(response.body().string(), is("FIRST|SECOND;"
                + "constructor-one|constructor-two;"
                + "fromString-one|fromString-two;"
                + "valueOf-one|valueOf-two"));
        }
    }

    @Test
    public void applicationConvertersAreAskedToConvertTheArrayElementType() throws IOException {
        AtomicReference<Class<?>> requestedRawType = new AtomicReference<>();
        AtomicReference<Type> requestedGenericType = new AtomicReference<>();

        class Converted {
            private final String value;

            private Converted(String value) {
                this.value = value;
            }
        }
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("value") Converted[] values) {
                return Arrays.stream(values).map(value -> value.value).collect(Collectors.joining("|"));
            }
        }
        ParamConverterProvider provider = new ParamConverterProvider() {
            @Override
            public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                if (!rawType.equals(Converted.class)) {
                    return null;
                }
                requestedRawType.set(rawType);
                requestedGenericType.set(genericType);
                return new ParamConverter<T>() {
                    @Override
                    public T fromString(String value) {
                        return rawType.cast(new Converted("converted-" + value));
                    }

                    @Override
                    public String toString(T value) {
                        return ((Converted) value).value;
                    }
                };
            }
        };
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())
            .addCustomParamConverterProvider(provider)).start();

        try (Response response = call(request(server.uri().resolve("/arrays?value=one&value=two")))) {
            assertThat(response.body().string(), is("converted-one|converted-two"));
        }
        assertThat(requestedRawType.get(), is(equalTo(Converted.class)));
        assertThat(requestedGenericType.get(), is(equalTo(Converted.class)));
    }

    @Test
    public void applicationConvertersReceiveGenericArrayElementTypes() throws IOException {
        AtomicReference<Type> requestedGenericType = new AtomicReference<>();

        class Holder<T> {
            private final T value;

            private Holder(T value) {
                this.value = value;
            }
        }
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("value") Holder<String>[] values) {
                return Arrays.stream(values).map(value -> value.value).collect(Collectors.joining("|"));
            }
        }
        ParamConverterProvider provider = new ParamConverterProvider() {
            @Override
            public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                if (!rawType.equals(Holder.class)) {
                    return null;
                }
                requestedGenericType.set(genericType);
                return new ParamConverter<T>() {
                    @Override
                    public T fromString(String value) {
                        return rawType.cast(new Holder<>("converted-" + value));
                    }

                    @Override
                    public String toString(T value) {
                        return ((Holder<?>) value).value.toString();
                    }
                };
            }
        };
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())
            .addCustomParamConverterProvider(provider)).start();

        try (Response response = call(request(server.uri().resolve("/arrays?value=one&value=two")))) {
            assertThat(response.body().string(), is("converted-one|converted-two"));
        }
        assertThat(requestedGenericType.get(), is(org.hamcrest.Matchers.instanceOf(ParameterizedType.class)));
        ParameterizedType genericType = (ParameterizedType) requestedGenericType.get();
        assertThat(genericType.getRawType(), is(equalTo(Holder.class)));
        assertThat(genericType.getActualTypeArguments(), is(equalTo(new Type[]{String.class})));
    }

    @Test
    public void arrayElementConversionFailuresAreClientErrorsForAllSupportedAnnotations() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            @Path("query")
            public String query(@QueryParam("value") Integer[] ignored) {
                return "unexpected";
            }

            @GET
            @Path("header")
            public String header(@HeaderParam("X-Value") Integer[] ignored) {
                return "unexpected";
            }

            @GET
            @Path("matrix")
            public String matrix(@MatrixParam("value") Integer[] ignored) {
                return "unexpected";
            }

            @POST
            @Path("form")
            public String form(@FormParam("value") Integer[] ignored) {
                return "unexpected";
            }

            @GET
            @Path("cookie")
            public String cookie(@CookieParam("value") Integer[] ignored) {
                return "unexpected";
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        assertBadRequest("/arrays/query?value=not-a-number", null);
        assertBadRequest("/arrays/header", request(server.uri().resolve("/arrays/header"))
            .header("X-Value", "not-a-number"));
        assertBadRequest("/arrays/matrix;value=not-a-number", null);
        try (Response response = call(request(server.uri().resolve("/arrays/form"))
            .post(new FormBody.Builder().add("value", "not-a-number").build()))) {
            assertThat(response.code(), is(400));
        }
        assertBadRequest("/arrays/cookie", request(server.uri().resolve("/arrays/cookie"))
            .header("Cookie", "value=not-a-number"));
    }

    @Test
    public void inheritedGenericArrayElementTypesAreResolved() throws IOException {
        class BaseResource<T> {
            @GET
            public String get(@QueryParam("value") T[] values) {
                return values.getClass().getComponentType().getName() + ":" + join(values);
            }
        }
        @Path("arrays")
        class ArrayResource extends BaseResource<Integer> {
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())).start();

        try (Response response = call(request(server.uri().resolve("/arrays?value=10&value=20")))) {
            assertThat(response.body().string(), is("java.lang.Integer:10|20"));
        }
    }

    @Test
    public void commaSplittingStrategyAppliesToArrays() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("value") String[] values) {
                return join(values);
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())
            .withCollectionParameterStrategy(CollectionParameterStrategy.SPLIT_ON_COMMA)).start();

        try (Response response = call(request(server.uri().resolve("/arrays?value=one,,%20two,")))) {
            assertThat(response.body().string(), is("one|two"));
        }
    }

    @Test
    public void openApiArrayDefaultsAreSingletonArrays() throws IOException {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@DefaultValue("42") @QueryParam("value") Integer[] values) {
                return join(values);
            }
        }
        server = httpsServerForTest().addHandler(restHandler(new ArrayResource())
            .withOpenApiJsonUrl("/openapi.json")).start();

        try (Response response = call(request(server.uri().resolve("/arrays")))) {
            assertThat(response.body().string(), is("42"));
        }
        try (Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertThat(response.code(), is(200));
            JSONObject schema = (JSONObject) new JSONObject(response.body().string())
                .query("/paths/~1arrays/get/parameters/0/schema");
            assertThat(schema.getString("type"), is("array"));
            assertThat(schema.getJSONObject("items").getString("type"), is("integer"));
            assertThat(schema.getJSONArray("default").length(), is(1));
            assertThat(schema.getJSONArray("default").getInt(0), is(42));
        }
    }

    @Test
    public void pathParamArraysRemainUnsupported() {
        @Path("arrays/{value}")
        class ArrayResource {
            @GET
            public String get(@PathParam("value") String[] ignored) {
                return "unexpected";
            }
        }

        try {
            restHandler(new ArrayResource()).build();
            Assert.fail("Should have rejected @PathParam arrays");
        } catch (MuException expected) {
            assertThat(expected.getMessage(), containsString("Could not find a suitable ParamConverter"));
        }
    }

    @Test
    public void muCookieArraysRemainUnsupported() {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@CookieParam("value") io.muserver.Cookie[] ignored) {
                return "unexpected";
            }
        }

        try {
            restHandler(new ArrayResource()).build();
            Assert.fail("Should have rejected Mu Cookie arrays");
        } catch (MuException expected) {
            assertThat(expected.getMessage(), containsString("io.muserver.Cookie[] is not supported"));
            assertThat(expected.getMessage(), containsString("jakarta.ws.rs.core.Cookie[]"));
        }
    }

    @Test
    public void primitiveArraysRemainUnsupported() {
        @Path("arrays")
        class ArrayResource {
            @GET
            public String get(@QueryParam("value") int[] ignored) {
                return "unexpected";
            }
        }

        try {
            restHandler(new ArrayResource()).build();
            Assert.fail("Should have rejected primitive arrays");
        } catch (MuException expected) {
            assertThat(expected.getMessage(), containsString("Could not find a suitable ParamConverter"));
        }
    }

    private void assertBadRequest(String path, okhttp3.Request.Builder requestBuilder) throws IOException {
        okhttp3.Request.Builder actualRequest = requestBuilder == null
            ? request(server.uri().resolve(path))
            : requestBuilder;
        try (Response response = call(actualRequest)) {
            assertThat(response.code(), is(400));
        }
    }

    private static String join(Object[] values) {
        return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining("|"));
    }

    private static String lengths(Object[]... arrays) {
        return Arrays.stream(arrays).map(array -> Integer.toString(array.length)).collect(Collectors.joining("|"));
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }
}
