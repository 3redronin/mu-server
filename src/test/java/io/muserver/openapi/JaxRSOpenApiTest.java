package io.muserver.openapi;

import io.muserver.MuServer;
import io.muserver.rest.RestHandlerBuilder;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

public class JaxRSOpenApiTest {

    private MuServer server;

    @Test
    public void subResourcesAreDoneCorrectly() throws Exception {

        class CarSubResource {
            public CarSubResource(String id) {
            }

            @GET
            @Produces(MediaType.APPLICATION_JSON)
            public String get() {
                return "a json car";
            }
            @GET
            @Produces(MediaType.TEXT_PLAIN)
            public String getText() {
                return "a text car";
            }
        }

        @Path("/api/cars")
        class Car {
            @Path("/{carId}")
            public CarSubResource carSubResource(@PathParam("carId") String id) {
                return new CarSubResource(id);
            }
        }

        server = httpServer()
            .addHandler(RestHandlerBuilder.restHandler(new Car())
                .withOpenApiJsonUrl("/openapi.json"))
            .start();

        JSONObject openApi;
        try (Response resp = ClientUtils.call(ClientUtils.request(server.uri().resolve("/openapi.json")))) {
            openApi = new JSONObject(resp.body().string());
        }
        JSONObject getOperation = (JSONObject) openApi.optQuery("/paths/~1api~1cars~1{carId}/get");
        assertThat(getOperation, notNullValue());
        JSONObject expected = new JSONObject("{\n" +
            "    \"requestBody\": {\n" +
            "        \"content\": {},\n" +
            "        \"required\": false\n" +
            "    },\n" +
            "    \"operationId\": \"GET_api_cars__carId_\",\n" +
            "    \"responses\": {\"200\": {\n" +
            "      \"description\": \"Success\",\n" +
            "      \"content\": {\n" +
            "        \"application/json\": {\n" +
            "          \"schema\": {\n" +
            "            \"nullable\": true,\n" +
            "            \"type\": \"string\"\n" +
            "          }\n" +
            "        },\n" +
            "        \"text/plain\": {\n" +
            "          \"schema\": {\n" +
            "            \"nullable\": true,\n" +
            "            \"type\": \"string\"\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }},\n" +
            "    \"parameters\": [{\n" +
            "      \"schema\": {\"type\": \"string\"},\n" +
            "      \"in\": \"path\",\n" +
            "      \"name\": \"carId\",\n" +
            "      \"required\": true\n" +
            "    }],\n" +
            "    \"tags\": [\"Car\"]\n" +
            "  }");
        assertThat(getOperation.toString(4), equalTo(expected.toString(4)));
    }

    @After
    public void tearDown() {
        MuAssert.stopAndCheck(server);
    }

}
