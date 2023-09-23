package scaffolding;

import io.muserver.Http2ConfigBuilder;
import io.muserver.MuServerBuilder;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;

public class ServerUtils {

    private static final String preferredProtocol = System.getenv().getOrDefault(
        "MU_TEST_PREFERRED_PROTOCOL", "HTTP2");

    public static MuServerBuilder httpsServerForTest() {
        MuServerBuilder builder = httpsServer();
        if (preferredProtocol.equals("HTTP2")) {
            builder.withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
        }
        return builder;
    }

    public static MuServerBuilder httpsServerForTest(String type) {
        return type.equalsIgnoreCase("http") ? httpServer() : httpsServerForTest();
    }

    public static MuServerBuilder testServer(String type) {
        return switch (type) {
            case "http" -> httpServer();
            case "https" -> httpsServer();
            default -> throw new IllegalArgumentException("Dunno this: " + type);
        };
    }

}
