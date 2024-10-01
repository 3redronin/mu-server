package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.openapi.SchemaObject;
import io.muserver.openapi.SchemaObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApiCustomSchemaTest {

    private MuServer server;

    @Test
    public void bodiesCanBeCustomized() throws Exception {

        class MyDao {
            public final String name;

            MyDao(String name) {
                this.name = name;
            }
        }

        @Produces("application/json")
        class DaoWriter implements MessageBodyWriter<MyDao> {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return MyDao.class.isAssignableFrom(type);
            }

            public void writeTo(MyDao dao, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                entityStream.write(dao.name.getBytes(UTF_8));
            }
        }

        @Path("/blah")
        class Blah {
            @GET
            @Produces("application/json")
            @Consumes("application/json")
            public MyDao get(MyDao inputDao) {
                return new MyDao("Dhalsim");
            }
        }

        server = httpsServerForTest()
            .addHandler(restHandler(new Blah())
                .addCustomWriter(new DaoWriter())
                .addCustomSchema(MyDao.class, schemaObject().withProperties(singletonMap("name", schemaObjectFrom(String.class).build())).withRequired(singletonList("name")).build())
                .withOpenApiJsonUrl("/openapi.json")
            )
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            assertThat(json.query("/paths/~1blah/get/responses/200/content/application~1json/schema/properties/name/type"), is("string"));
            assertThat(json.query("/paths/~1blah/get/responses/200/content/application~1json/schema").toString(), equalTo(json.query("/paths/~1blah/get/requestBody/content/application~1json/schema").toString()));
        }
    }

    interface MyWritable {
        String toJSON();
    }

    @Test
    public void genericTypesCanBeCustomized() throws Exception {

        class SearchResults<T extends MyWritable> implements MyWritable {
            public final int count;
            public final List<T> items;

            SearchResults(int count, List<T> items) {
                this.count = count;
                this.items = items;
            }

            @Override
            public String toJSON() {
                return "{\"count\": " + count + ", items=["
                    + items.stream().map(MyWritable::toJSON).collect(Collectors.joining(", "))
                    + "]}";
            }

        }

        class Product implements MyWritable {
            public final String name;

            Product(String name) {
                this.name = name;
            }

            @Override
            public String toJSON() {
                return "{\"name\": \"" + name + "\"}";
            }

            public Map<String,Object> toMap() {
                return singletonMap("name", name);
            }
        }

        class Person implements MyWritable {
            public final int age;

            Person(int age) {
                this.age = age;
            }

            @Override
            public String toJSON() {
                return "{ \"age\": " + age + "}";
            }

            public Map<String,Object> toMap() {
                return singletonMap("age", age);
            }
        }

        @Produces("application/json")
        class SearchResultsWriter implements MessageBodyWriter<SearchResults<?>> {
            public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
                return SearchResults.class.isAssignableFrom(type);
            }

            public void writeTo(SearchResults<?> results, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException {
                entityStream.write(results.toJSON().getBytes(UTF_8));
            }
        }

        @Path("/blah")
        class Blah implements SchemaObjectCustomizer {
            @GET
            @Path("/products")
            @Produces("application/json")
            public SearchResults<Product> getProducts() {
                return null;
            }

            @GET
            @Path("/people")
            @Produces("application/json")
            public SearchResults<Person> getPeople() {
                return null;
            }

            @Override
            public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
                if (context.type().equals(SearchResults.class) && context.parameterizedType().isPresent()) {
                    Type type = context.parameterizedType().get();
                    if (type instanceof ParameterizedType) {
                        ParameterizedType pt = (ParameterizedType) type;
                        if (pt.getActualTypeArguments().length == 1) {
                            Type gt = pt.getActualTypeArguments()[0];

                            SchemaObject itemsSchema = null;
                            if (gt.equals(Person.class)) {
                                itemsSchema = schemaObject().withType("object").withProperties(singletonMap("age", schemaObjectFrom(int.class).build())).withRequired(singletonList("age")).withExample(new Person(99).toMap()).build();
                            } else if (gt.equals(Product.class)) {
                                itemsSchema = schemaObject().withType("object").withProperties(singletonMap("name", schemaObjectFrom(String.class).build())).withRequired(singletonList("name")).withExample(new Product("Computer").toMap()).build();
                            }
                            if (itemsSchema != null) {
                                Map<String, SchemaObject> properties = new HashMap<>(builder.properties());
                                properties.put("items", properties.get("items").toBuilder().withItems(itemsSchema).build());
                                builder.withProperties(properties);
                            }
                        }
                    }

                }
                return builder;
            }
        }

        Map<String, SchemaObject> searchResultsProps = new HashMap<>();
        searchResultsProps.put("count", schemaObjectFrom(int.class).build());
        searchResultsProps.put("items", schemaObjectFrom(List.class).build());
        SchemaObject searchResultsSchemaObject = schemaObject().withProperties(searchResultsProps).withRequired(asList("count", "items")).build();

        server = httpsServerForTest()
            .addHandler(restHandler(new Blah())
                .addCustomWriter(new SearchResultsWriter())
                .addCustomSchema(SearchResults.class, searchResultsSchemaObject)
                .withOpenApiJsonUrl("/openapi.json")
            )
            .start();
        try (okhttp3.Response resp = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject json = new JSONObject(resp.body().string());
            JSONObject peopleItems = (JSONObject) json.query("/paths/~1blah~1people/get/responses/200/content/application~1json/schema/properties/items");
            assertThat(peopleItems.getString("type"), is("array"));
            assertThat(peopleItems.query("/items/properties/age/format"), is("int32"));
            JSONObject productItems = (JSONObject) json.query("/paths/~1blah~1products/get/responses/200/content/application~1json/schema/properties/items");
            assertThat(productItems.getString("type"), is("array"));
            assertThat(productItems.query("/items/properties/name/type"), is("string"));
        }
    }

    @AfterEach
    public void cleanup() {
        MuAssert.stopAndCheck(server);
    }

}
