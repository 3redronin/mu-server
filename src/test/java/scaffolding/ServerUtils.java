package scaffolding;

import io.muserver.Http2ConfigBuilder;
import io.muserver.MuServerBuilder;

import static io.muserver.MuServerBuilder.httpsServer;

public class ServerUtils {
    public static MuServerBuilder httpsServerForTest() {
        return httpsServer()
            .withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
    }
}
