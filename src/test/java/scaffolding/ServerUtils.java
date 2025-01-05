package scaffolding;

import io.muserver.Http2ConfigBuilder;
import io.muserver.MuServerBuilder;

import static io.muserver.MuServerBuilder.muServer;

public class ServerUtils {


    public static MuServerBuilder httpsServerForTest() {
        return httpsServerForTest("https");
    }
    public static MuServerBuilder httpsServerForTest(String protocol) {
        MuServerBuilder builder = muServer();
        switch (protocol) {
            case "http":
                builder.withHttpPort(0);
                break;
            case "https":
                builder.withHttpsPort(0)
                    .withHttp2Config(Http2ConfigBuilder.http2Disabled());
                break;
            case "h2":
                builder.withHttpsPort(0);
                break;
            default:
                throw new IllegalArgumentException("Unsupported protocol: " + protocol);
        }
        return builder;
    }
}
