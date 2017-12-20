import ronin.muserver.*;

import static ronin.muserver.MuServerBuilder.muServer;

public class RunLocal {

    public static void main(String[] args) {
        MuServer server = muServer()
            .withHttpConnection(8080)
            .withHttpsConnection(8443, SSLContextBuilder.unsignedLocalhostCert())
            .withGzipEnabled(true)
            .addHandler(Method.GET, "/", (request, response) -> {
                response.headers().add(HeaderNames.CONTENT_TYPE, HeaderValues.APPLICATION_JSON + ";charset=utf-8");
                String text = "{ \"hello\": \"world                    this is something           to be gzipped\" }";
                response.headers().add(HeaderNames.CONTENT_LENGTH, text.length());
                response.write(text);
                return true;
            })
            .start();

        System.out.println("Started at " + server.uri() + " and " + server.httpsUri());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

}
