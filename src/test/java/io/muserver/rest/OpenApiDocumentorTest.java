package io.muserver.rest;

import io.muserver.MuServer;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class OpenApiDocumentorTest {

    private final MuServer server = httpServer()
        .addHandler(RestHandlerBuilder.restHandler(
            new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource()
        ))
        .start();

    @Test
    public void hasJsonEndpoint() throws IOException {
        try (okhttp3.Response resp = call(request().url(server.uri().resolve("/openapi.json").toString()))) {
            assertThat(resp.code(), is(200));
            JSONObject json = new JSONObject(resp.body().string());
            System.out.println(json.toString(4));
            JSONObject paths = json.getJSONObject("paths");

            JSONObject pet = paths.getJSONObject("/pet");
            assertThat(pet.keySet(), containsInAnyOrder("put", "post"));

            JSONObject download = paths.getJSONObject("/pet/{petId}/download").getJSONObject("get");
            assertThat(download.getBoolean("deprecated"), is(false));
            JSONArray downloadParams = download.getJSONArray("parameters");
            assertThat(downloadParams.length(), is(1));
            JSONObject downloadParam1 = downloadParams.getJSONObject(0);
            assertThat(downloadParam1.getString("name"), equalTo("petId"));


            JSONObject findByTags = paths.getJSONObject("/pet/findByTags").getJSONObject("get");
            assertThat(findByTags.getBoolean("deprecated"), is(true));
            JSONObject findByTagsParam = findByTags.getJSONArray("parameters").getJSONObject(1);
            assertThat(findByTagsParam.getString("name"), equalTo("tags"));
            assertThat(findByTagsParam.getString("in"), equalTo("query"));
            assertThat(findByTagsParam.getJSONObject("schema").getString("type"), equalTo("string"));

            System.out.println(findByTagsParam.toString(4));
        }
    }


}
