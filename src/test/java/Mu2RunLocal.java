import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpsServer;

public class Mu2RunLocal {
    private static final Logger log = LoggerFactory.getLogger(Mu2RunLocal.class);
    public static void main(String[] args) throws Exception {
        var server = httpsServer()
            .withHttpsPort(10000)
            .addHandler((request, response) -> {
                response.sendChunk("Ah!!!!!!");
                log.info("Chunk sent");
//                new Thread(() -> request.server().stop(5, TimeUnit.SECONDS)).start();
                Thread.sleep(1000);
                response.sendChunk(" done.");
                log.info("Handler ending");
                return true;
            })
            .start2();
        log.info("server.uri() = " + server.uri());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            server.stop(5, TimeUnit.SECONDS);
            log.info("Shut down");
        }));

        System.in.read();
    }
}
