import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;
import io.muserver.handlers.ResourceHandler;

import static io.muserver.MuServerBuilder.muServer;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpConnection(8080)
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(ResourceHandler.fileOrClasspath("src/test/resources/sample-static", "/sample-static").build())
            .addHandler(Method.GET, "/api", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write("{ \"hello\": \"world                    this is something           to be gzipped\" }");
            })
            .start();

        System.out.println("Started at " + server.httpUri() + " and " + server.httpsUri());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
