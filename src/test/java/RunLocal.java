import ronin.muserver.*;
import ronin.muserver.handlers.ResourceHandler;

import static ronin.muserver.MuServerBuilder.muServer;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpConnection(8080)
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(ResourceHandler.fileOrClasspath("src/test/resources/sample-static", "/sample-static").build())
            .addHandler(Method.GET, "/api", (request, response) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write("{ \"hello\": \"world                    this is something           to be gzipped\" }");
                return true;
            })
            .start();

        System.out.println("Started at " + server.httpUri() + " and " + server.httpsUri());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
