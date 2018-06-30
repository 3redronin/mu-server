import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.rest.RestHandlerBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

public class DocumentationExamples {
    public static void main(String[] args) {
        @Path("/fruits")
        class Fruit {
            @GET
            public void all() {

            }
        }

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(14400)
            .addHandler(
                RestHandlerBuilder.restHandler(new Fruit(), new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource())
                    .withOpenApiHtmlUrl("/docs.html")
                    .withOpenApiJsonUrl("/api.json")
            ).start();
        System.out.println("Browse documentation at " + server.uri().resolve("/docs.html")
            + " and " + server.uri().resolve("/api.json"));
    }
}
