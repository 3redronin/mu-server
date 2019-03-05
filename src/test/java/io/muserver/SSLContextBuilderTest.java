package io.muserver;

import okhttp3.Response;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;

public class SSLContextBuilderTest {

    @Test public void canCreateAnUnsignedOne() {
        SSLContextBuilder.unsignedLocalhostCert();
    }

    @Test public void canCreateADefaultOne() {
        SSLContextBuilder.defaultSSLContext();
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfTheClasspathIsInvalid() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystoreFromClasspath("/io/muserver/resources/wrong.jks");
    }

    @Test
    public void canCreateFromTheFileSystem() throws IOException {
        SSLContextBuilder sslContextBuilder = SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/main/resources/io/muserver/resources/localhost.jks"));
        test(sslContextBuilder);
    }

    @Test
    public void canCreateFromTheClasspath() throws IOException {
        SSLContextBuilder sslContextBuilder = SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystoreFromClasspath("/io/muserver/resources/localhost.jks");
        test(sslContextBuilder);
    }

    @Test
    public void canCreateFromInputStream() throws IOException {
        SSLContextBuilder sslContextBuilder = SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new FileInputStream("src/main/resources/io/muserver/resources/localhost.jks"));
        test(sslContextBuilder);
    }

    @Test
    public void protocolsCanBeSpecified() throws Exception {
        SSLContextBuilder sslContextBuilder = SSLContextBuilder.unsignedLocalhostCertBuilder();
        MuServer server = httpsServer()
            .withHttpsConfig(sslContextBuilder)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
            .start();
        String cipher;
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Hello"));
            cipher = getCipher(server.uri());
        } finally {
            MuAssert.stopAndCheck(server);
        }

        sslContextBuilder = SSLContextBuilder.unsignedLocalhostCertBuilder()
        .withCipherFilter((supportedCiphers, defaultCiphers) -> {
            List<String> selected = new ArrayList<>(defaultCiphers);
            selected.remove(cipher);
            return selected;
        });
        server = httpsServer()
            .withHttpsConfig(sslContextBuilder)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Hello"));
            assertThat(getCipher(server.uri()), not(equalTo(cipher)));
        } finally {
            MuAssert.stopAndCheck(server);
        }

    }

    private String getCipher(URI uri) throws Exception {
        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((s, sslSession) -> true);
        HttpsURLConnection conn = (HttpsURLConnection) uri.toURL().openConnection();
        conn.connect();
        return conn.getCipherSuite();
    }

    private static void test(SSLContextBuilder sslContextBuilder) throws IOException {
        MuServer server = httpsServer()
            .withHttpsConfig(sslContextBuilder)
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write("Hello"))
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Hello"));
        } finally {
            MuAssert.stopAndCheck(server);
        }
    }

    @Test(expected = MuException.class)
    public void throwsIfThePasswordIsWrong() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecured")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/main/resources/io/muserver/resources/localhost.jks"))
            .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void throwsIfTheFileDoesNotExist() {
        SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystore(new File("src/test/blah"));
    }
}