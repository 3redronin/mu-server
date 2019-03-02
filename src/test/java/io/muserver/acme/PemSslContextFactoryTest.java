package io.muserver.acme;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.io.File;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class PemSslContextFactoryTest {

    private MuServer server;
    private final File certFile = new File("src/test/resources/example-config-dir/cert-chain.crt");
    private final File keyFile = new File("src/test/resources/example-config-dir/domain-key.pem");

    @Test
    public void letsEncryptCertsCanBeUsed() throws Exception {

        SSLContext context = PemSslContextFactory.getSSLContextFromLetsEncrypt(certFile, keyFile);

        server = httpsServer()
            .withHttpsConfig(context)
            .addHandler((req, resp) -> {
                resp.write("Hello");
                return true;
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("Hello"));
        }

    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}