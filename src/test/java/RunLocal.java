import io.muserver.*;
import io.muserver.handlers.ResourceHandlerBuilder;
import io.muserver.rest.CORSConfigBuilder;
import io.muserver.rest.RestHandlerBuilder;
import org.example.petstore.resource.PetResource;
import org.example.petstore.resource.PetStoreResource;
import org.example.petstore.resource.UserResource;
import org.example.petstore.resource.VehicleResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import static io.muserver.Mutils.urlEncode;
import static io.muserver.handlers.FileProviderTest.BIG_FILE_DIR;

public class RunLocal {
    private static final Logger log = LoggerFactory.getLogger(RunLocal.class);

    public static void main(String[] args) {
        MuServer server = new MuServerBuilder().withHttpPort(0).withHttpsPort(0)
            .withHttpPort(18080)
            .withHttpsPort(18443)
            .withHttpsConfig(SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(ResourceHandlerBuilder.fileHandler(BIG_FILE_DIR))
            .addHandler(ResourceHandlerBuilder.fileOrClasspath("src/test/resources/sample-static", "/sample-static"))
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
                    .withCORS(CORSConfigBuilder.corsConfig().withAllowedOrigins("http://localhost:3200").withExposedHeaders("Content-Type"))
            )
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                UploadedFile file = request.uploadedFile("theFile");
                response.contentType(file.contentType());
                response.headers().set(HeaderNames.CONTENT_LENGTH, file.size());
                boolean ticked = request.form().getBoolean("ticked");
                log.info("Form parameters: " + request.form().get("blah") + " - " + ticked);
                log.info("Going to send " + file.size() + " bytes as " + file.contentType());
                try (InputStream fileStream = file.asStream();
                     OutputStream out = response.outputStream()) {
                    Mutils.copy(fileStream, out, 8192);
                }
            })
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


            .addHandler(Method.GET, "/streamer", (request, response, pathParams) -> {
                int startValue = request.headers().getInt(HeaderNames.LAST_EVENT_ID, 1);
                log.info("Starting event stream at " + startValue);
                SsePublisher ssePublisher = SsePublisher.start(request, response);
                new Thread(() -> {
                    try {
                        for (int i = startValue; i < startValue + 10; i++) {
                            ssePublisher.send("This is message " + i, null, String.valueOf(i));
                            Thread.sleep(1000);
                        }
                    } catch (Exception e) {
                        // the user has probably disconnected; stop publishing
                        log.info("Error while publishing to event stream", e);
                    } finally {
                        ssePublisher.close();
                    }
                }).start();
            })
            .addHandler(Method.GET, "/stats", (request, response, pathParams) -> {
                MuStats stats = request.server().stats();
                response.contentType(ContentTypes.TEXT_PLAIN);
                response.sendChunk(stats.toString());
                response.sendChunk("\n\n");
                for (MuRequest muRequest : stats.activeRequests()) {
                    response.sendChunk(muRequest + "\r\n");
                }
            })
            .start();


        log.info("Started at " + server.httpUri() + " and " + server.httpsUri());
        log.info("REST docs available at " + server.httpUri().resolve("/api.html") + " and OpenAPI JSON at " + server.httpUri().resolve("/openapi.json"));

        File[] files = BIG_FILE_DIR.listFiles(File::isFile);
        for (File file : files) {
            URI downloadUri = server.httpUri().resolve("/" + urlEncode(file.getName()));
            log.info("Download " + file.getName() + " from " + downloadUri);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
