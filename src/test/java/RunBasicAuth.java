import io.muserver.rest.BasicAuthTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RunBasicAuth {
    private static final Logger log = LoggerFactory.getLogger(RunBasicAuth.class);

    public static void main(String[] args) {
        // You can test this test with a browser
        BasicAuthTest ctx = new BasicAuthTest();
        ctx.port = 12500;
        ctx.setup();

        Runtime.getRuntime().addShutdownHook(new Thread(ctx::stop));

        log.info("Started at " + ctx.server.uri());
    }

}
