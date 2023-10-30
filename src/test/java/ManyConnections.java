import io.muserver.HeaderNames;
import io.muserver.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.Http1Client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;

public class ManyConnections {

    private static final Logger log = LoggerFactory.getLogger(ManyConnections.class);
    public static void main(String[] args) throws InterruptedException {
        try {
            go();
        } catch (Throwable e) {
            log.error("Error from main", e);
            System.exit(1);
        }
    }
    public static void go() throws InterruptedException {

        var exceptions = new HashMap<Integer, Exception>();

        var server = httpServer()
                .withRequestTimeout(5, TimeUnit.MINUTES)
                .withHandshakeIOTimeout(5, TimeUnit.MINUTES)
                .withIdleTimeout(5, TimeUnit.MINUTES)
                .withResponseWriteTimeoutMillis(5, TimeUnit.MINUTES)
                .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                    response.contentType("text/plain");
                    int sends = 10;
                    response.headers().set(HeaderNames.CONTENT_LENGTH, sends + 1);

                    for (int i = 0; i < sends; i++) {
                        response.writer().write("*");
                        Thread.sleep(1000);
                    }
                    response.sendChunk("!");
                })
                .start();

        var clients = new ArrayList<Http1Client>();
        var numberOfClients = 1000;
        var executor = Executors.newScheduledThreadPool(numberOfClients);

        for (int i = 0; i < numberOfClients; i++) {
            int finalI = i;
            long start = System.nanoTime();
            try {
                Http1Client http1Client = Http1Client.connect(server.uri());
                log.info("Connected " + finalI + " in " + (System.nanoTime() - start));
                clients.add(http1Client);

                boolean doRequest = true;
                if (doRequest) {
                    http1Client.writeRequestLine(Method.GET, "/").flushHeaders();
                    executor.schedule(() -> {
                        log.info("Sending request for " + finalI);
                        try {
                            http1Client.readLine();
                            http1Client.readBody(http1Client.readHeaders());
                            log.info("Client " + finalI + " read complete");
                        } catch (Exception e) {
                            log.info("Exception on client " + finalI, e);
                        }
                    }, 1000, TimeUnit.MILLISECONDS);
                }
            } catch (Exception e) {
                log.error("Errored " + finalI, e);
                exceptions.put(i, e);
                return;
            }

        }

        int connectionsStart = server.activeConnections().size();
        log.info("There are " + connectionsStart + " connections");


        executor.shutdown();
        log.info("Awaiting executor shutdown");
        var worked = executor.awaitTermination(90, TimeUnit.SECONDS);
        log.info("Executor shutdown worked? " + worked);

        log.info("Completed");
        int connectionsEnd = server.activeConnections().size();
        log.info("There are " + connectionsEnd + " connections");
        for (Http1Client client : clients) {
            client.close();
        }
        log.info("The end");

        server.stop();

        Thread.sleep(500);
        log.info("Result: " + connectionsStart + "/" + connectionsEnd + " errors=" + exceptions);
    }

}
