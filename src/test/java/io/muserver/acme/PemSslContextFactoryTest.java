package io.muserver.acme;

import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class PemSslContextFactoryTest {

    private MuServer server;
    private final File certFile = new File("src/test/resources/example-config-dir/cert-chain.crt");
    private final File keyFile = new File("src/test/resources/example-config-dir/domain-key.pem");

    @Test
    public void letsEncryptCertsCanBeUsed() throws Exception {

        KeyManagerFactory keyManagerFactory = PemSslContextFactory.getKeyManagerFactory(certFile, keyFile);

        server = httpsServer()
            .withHttpsConfig(SSLContextBuilder.sslContext().withKeyManagerFactory(keyManagerFactory))
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