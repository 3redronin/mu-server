import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.rest.RestHandlerBuilder;

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

MuServer server = MuServerBuilder.httpsServer()
    .addHandler(
        RestHandlerBuilder.restHandler(new Fruit())
            .withDocumentation()
            .withOpenApiHtmlUrl("/docs.html")
            .withOpenApiJsonUrl("/api.json")
    ).start();
System.out.println("Browse documentation at " + server.uri().resolve("/docs.html")
    + " and " + server.uri().resolve("/api.json"));
    }
}
