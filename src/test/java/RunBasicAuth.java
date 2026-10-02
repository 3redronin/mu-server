import io.muserver.rest.BasicAuthTest;

public class RunBasicAuth {

    public static void main(String[] args) {
        // You can test this test with a browser
        BasicAuthTest ctx = new BasicAuthTest();
        ctx.port = 12500;
        ctx.setup();

        Runtime.getRuntime().addShutdownHook(new Thread(ctx::stop));

    }

}
