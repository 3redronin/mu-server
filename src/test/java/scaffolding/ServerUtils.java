package scaffolding;

import io.muserver.Http2ConfigBuilder;
import io.muserver.MuServerBuilder;

import static io.muserver.MuServerBuilder.muServer;

public class ServerUtils {

    private static final String preferredProtocol = System.getenv().getOrDefault(
        "MU_TEST_PREFERRED_PROTOCOL", "HTTP2");

    public static MuServerBuilder httpsServerForTest() {
        return httpsServerForTest("https");
    }
    public static MuServerBuilder httpsServerForTest(String protocol) {
        MuServerBuilder builder = muServer();
        if (protocol.equals("http")) {
            builder.withHttpPort(0);
        } else if (protocol.equals("https")) {
            builder.withHttpsPort(0);
        } else throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        if (preferredProtocol.equals("HTTP2")) {
            builder.withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
        }
        return builder;
    }
}
