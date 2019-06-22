package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;

public class HttpsTest {

    private MuServer server;

    @Test
    public void canGetInfoAboutHttps() throws Exception {
        AtomicReference<SSLInfo> actualSSLInfo = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest().withHttpsConfig(SSLContextBuilder.unsignedLocalhostCert())
            .addHandler((request, response) -> {
                actualSSLInfo.set(request.server().sslInfo());
                response.write("This is encrypted and the URL is " + request.uri());
                return true;
            })
            .start();

        try (Response resp = call(request(server.httpsUri()))) {
            assertThat(resp.body().string(), equalTo("This is encrypted and the URL is https://localhost:"
                + server.httpsUri().getPort() + "/"));
        }
        SSLInfo sslInfo = actualSSLInfo.get();
        assertThat(sslInfo.providerName(), isOneOf("JDK", "OpenSSL"));
        assertThat(sslInfo.protocols(), hasItem("TLSv1.2"));
        assertThat(sslInfo.ciphers().size(), greaterThan(0));

        List<X509Certificate> certificates = sslInfo.certificates();
        assertThat(certificates, hasSize(1));
        assertThat(certificates.get(0).getNotAfter(), equalTo(new Date(4714779311000L)));
        assertThat(certificates, equalTo(sslInfo.certificates())); // check that cached calls work
    }

    @Test
    public void httpIsNotAvailableUnlessRequested() {
        server = ServerUtils.httpsServerForTest().start();
        assertThat(server.httpUri(), is(nullValue()));
    }

    @Test
    public void sslInfoIsNullForHttp() throws IOException {
        server = httpServer()
            .addHandler((req, res) -> {
                res.write("SSLInfo: " + req.server().sslInfo());
                return true;
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("SSLInfo: null"));
        }
    }

    @Test
    public void certsCanBeChangedWhileRunning() throws Exception {

        /*
        Note: certs generated with:
        keytool -genkeypair -keystore jks-keystore.jks -storetype JKS -storepass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=My JKS Certificate, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:localhost,ip:127.0.0.1
        keytool -genkeypair -keystore pkcs12-keystore.p12 -storetype PKCS12 -storepass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=My PKCS12 Certificate, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:localhost,ip:127.0.0.1
         */

        SSLContextBuilder originalCert = SSLContextBuilder.sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("MY_PASSWORD")
            .withKeyPassword("MY_PASSWORD")
            .withKeystoreFromClasspath("/jks-keystore.jks");

        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(originalCert)
            .addHandler((request, response) -> {
                response.write("This is encrypted");
                return true;
            })
            .start();
        try (Response resp = call(request(server.httpsUri()))) {
            assertThat(resp.body().string(), equalTo("This is encrypted"));
        }

        assertThat(certInformation(server.uri()), containsString("My JKS Certificate"));

        SSLContextBuilder newCert = SSLContextBuilder.sslContext()
            .withKeystoreType("PKCS12")
            .withKeystorePassword("MY_PASSWORD")
            .withKeyPassword("MY_PASSWORD")
            .withKeystoreFromClasspath("/pkcs12-keystore.p12");

        server.changeSSLContext(newCert);

        try (Response resp = call(request(server.httpsUri()))) {
            assertThat(resp.body().string(), equalTo("This is encrypted"));
        }

        assertThat(certInformation(server.uri()), containsString("My PKCS12 Certificate"));

    }

    private static String certInformation(URI uri) throws Exception{
        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection conn = (HttpsURLConnection) uri.toURL().openConnection();
        conn.connect();
        Certificate[] certs = conn.getServerCertificates();
        StringBuilder sb = new StringBuilder();
        for (Certificate cert : certs) {
            sb.append("Certificate is: ").append(cert);
            if(cert instanceof X509Certificate) {
                X509Certificate x = (X509Certificate) cert;
                sb.append(x.getIssuerDN());
            }
        }
        return sb.toString();
    }

    @After public void stopIt() {
        MuAssert.stopAndCheck(server);
    }
}
