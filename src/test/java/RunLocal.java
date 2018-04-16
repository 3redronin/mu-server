import io.muserver.*;
import io.muserver.handlers.ResourceHandler;
import io.muserver.rest.RestHandlerBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;

import java.io.File;
import java.net.URI;

import static io.muserver.Mutils.urlEncode;
import static io.muserver.handlers.FileProviderTest.BIG_FILE_DIR;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = new MuServerBuilder().withHttpPort(0).withHttpsPort(0)
            .withHttpPort(18080).withHttpsPort(18443).withHttpsConfig(SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(ResourceHandler.fileHandler(BIG_FILE_DIR).build())
            .addHandler(ResourceHandler.fileOrClasspath("src/test/resources/sample-static", "/sample-static").build())
            .addHandler(Method.GET, "/api", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write("{ \"hello\": \"world                    this is something           to be gzipped\" }");
            })
            .addHandler(
                RestHandlerBuilder.restHandler(
                    new PetResource(), new PetStoreResource(), new UserResource(), new VehicleResource()
                )
                    .withOpenApiJsonUrl("/openapi.json")
                    .withOpenApiHtmlUrl("/api.html")
            )
            .addHandler(Method.GET, "/stream", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN);
                for (int i = 0; i < Integer.MAX_VALUE; i++) {
                    if ((i % 100) == 0) {
                        response.sendChunk("*");
                    } else {
                        response.sendChunk(".");
                    }
                    Thread.sleep(10);
                }
            })
            .start();

        System.out.println("Started at " + server.httpUri() + " and " + server.httpsUri());
        System.out.println("REST docs available at " + server.httpUri().resolve("/api.html") + " and OpenAPI JSON at " + server.httpUri().resolve("/openapi.json"));

        File[] files = BIG_FILE_DIR.listFiles(File::isFile);
        for (File file : files) {
            URI downloadUri = server.httpUri().resolve("/" + urlEncode(file.getName()));
            System.out.println("Download " + file.getName() + " from " + downloadUri);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
