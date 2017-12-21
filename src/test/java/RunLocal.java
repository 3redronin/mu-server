import ronin.muserver.*;

import static ronin.muserver.MuServerBuilder.muServer;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpConnection(8080)
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(Method.GET, "/", (request, response) -> {
                response.headers().add(HeaderNames.CONTENT_TYPE, HeaderValues.APPLICATION_JSON);
                response.write("{ \"hello\": \"world                    this is something           to be gzipped\" }");
                return true;
            })
            .start();

        System.out.println("Started at " + server.uri() + " and " + server.httpsUri());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
