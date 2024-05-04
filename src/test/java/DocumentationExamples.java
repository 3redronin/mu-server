import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.rest.CORSConfigBuilder;
import io.muserver.rest.Description;
import io.muserver.rest.Required;
import io.muserver.rest.RestHandlerBuilder;
import jakarta.ws.rs.*;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;

import java.time.*;
import java.util.UUID;

import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;

public class DocumentationExamples {
    public static void main(String[] args) {
        @Path("/fruits")
        @Description(value = "Fruits", documentationUrl = "https://fruits.example.org", details = "The details of the request class")
        class Fruit {
            @POST
            @Produces("text/plain")
            @Description(value = "A method", documentationUrl = "https://get.example.org", details = "The details of the method")
            public String all(@QueryParam("jam") @Required @DefaultValue("strawberry") @Description(value = "The jam", example = "Mango", documentationUrl = "http://example.org/looooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooong-url", details = "The details of the param")
                                  String jam,
                              @QueryParam("uuid") UUID uuid,
                              @Description(value = "Anything", details = "The details of the request body", documentationUrl = "https://anything.example.org")
                                  String body) {
                return "jam=" + jam + "; uuid=" + uuid + "; body=" + body;
            }

            @GET
            @Path("/dates")
            public LocalDate dates(
                @QueryParam("uuid") UUID uuid,
                @QueryParam("instant") Instant instant,
                @QueryParam("localDate") LocalDate localDate,
                @QueryParam("localTime") LocalTime localTime,
                @QueryParam("localDateTime") LocalDateTime localDateTime,
                @QueryParam("offsetTime") OffsetTime offsetTime,
                @QueryParam("offsetDateTime") OffsetDateTime offsetDateTime,
                @QueryParam("zonedDateTime") ZonedDateTime zonedDateTime,
                @QueryParam("year") Year year,
                @QueryParam("yearMonth") YearMonth yearMonth
            ) {
                return localDate;
            }
        }

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(14400)
            .addHandler(
                RestHandlerBuilder.restHandler(new Fruit(), new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource())
                    .withOpenApiHtmlUrl("/docs.html")
                    .withOpenApiJsonUrl("/api.json")
                    .withCORS(CORSConfigBuilder.corsConfig().withAllOriginsAllowed()
                        .withAllowedHeaders("content-type")
                    )
                    .addCustomSchema(Fruit.class, schemaObject().build())
            ).start();
        System.out.println("Browse documentation at " + server.uri().resolve("/docs.html")
            + " and " + server.uri().resolve("/api.json"));
    }
}
