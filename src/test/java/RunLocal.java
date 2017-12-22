import ronin.muserver.*;
import ronin.muserver.handlers.ResourceHandler;

import static ronin.muserver.MuServerBuilder.muServer;
import static ronin.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpConnection(8080)
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(new ResourceHandler("src/test/resources/sample-static", "/", "index.html", DEFAULT_EXTENSION_MAPPINGS))
            .addHandler(Method.GET, "/api", (request, response) -> {
                response.headers().add(HeaderNames.CONTENT_TYPE, ContentTypes.APPLICATION_JSON);
                response.write("{ \"hello\": \"world                    this is something           to be gzipped\" }");
                return true;
            })
            .start();

        System.out.println("Started at " + server.uri() + " and " + server.httpsUri());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
