import io.muserver.*;
import io.muserver.acme.*;
import io.muserver.handlers.HttpsRedirectorBuilder;

import java.util.concurrent.TimeUnit;

public class QuickStart {

    public static void main(String[] args) throws Exception {

        AcmeCertManager certManager = AcmeCertManagerBuilder.letsEncryptStaging()
            .withDomain("your-domain.example.org")
            .withConfigDir("target/ssl-config")
            .build();

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(80)
            .withHttpsPort(443)
            .withHttpsConfig(certManager.createSSLContext())
            .addHandler(certManager.createHandler())
            .addHandler(
                HttpsRedirectorBuilder.toHttpsPort(443)
                    .withHSTSExpireTime(1, TimeUnit.DAYS)
                    .includeSubDomains(true)
            )
            .addHandler(Method.GET, "/", (req, resp, path) -> {
                resp.write("Hello, world");
            })
            .start();

        certManager.start(server);
        System.out.println("Started server at " + server.uri());

    }

}
