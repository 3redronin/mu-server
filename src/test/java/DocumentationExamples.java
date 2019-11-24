import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.rest.CORSConfigBuilder;
import io.muserver.rest.Description;
import io.muserver.rest.Required;
import io.muserver.rest.RestHandlerBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

public class DocumentationExamples {
    public static void main(String[] args) {
        @Path("/fruits")
        @Description(value="Fruits", documentationUrl = "https://fruits.example.org", details = "The details of the request class")
        class Fruit {
            @GET
            @Description(value="A method", documentationUrl = "https://get.example.org", details = "The details of the method")
            public void all(@QueryParam("jam") @Required @DefaultValue("strawberry") @Description(value = "The jam", example = "Mango", documentationUrl = "http://example.org/looooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooong-url", details = "The details of the param")
                                String jam,
                            @Description(value="Anything", details = "The details of the request body", documentationUrl = "https://anything.example.org")
                            String body) {

            }
        }

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(14400)
            .addHandler(
                RestHandlerBuilder.restHandler(new Fruit(), new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource())
                    .withOpenApiHtmlUrl("/docs.html")
                    .withOpenApiJsonUrl("/api.json")
                    .withCORS(CORSConfigBuilder.corsConfig().withAllOriginsAllowed())
            ).start();
        System.out.println("Browse documentation at " + server.uri().resolve("/docs.html")
            + " and " + server.uri().resolve("/api.json"));
    }
}
