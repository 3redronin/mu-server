package io.muserver;

import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import scaffolding.SSLSocketFactoryWrapper;
import scaffolding.ServerUtils;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.URI;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.*;

public class HttpsTest {

    private MuServer server;

    @Test
    public void canGetInfoAboutHttps() throws Exception {
        AtomicReference<SSLInfo> actualSSLInfo = new AtomicReference<>();
        server = ServerUtils.httpsServerForTest().withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost())
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
        assertThat(sslInfo.providerName(), oneOf("JDK", "OpenSSL"));
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
    public void canChooseCertBySan() throws Exception {

        /* jks-keystore-combine.jks generated with:
        keytool -genkeypair -keystore jks-keystore-combine.jks -storetype JKS -alias mykey-1 -storepass MY_PASSWORD -keypass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=TEST-1, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:test-1.com,dns:localhost,ip:127.0.0.1
        keytool -genkeypair -keystore jks-keystore-temp-2.jks -storetype JKS -alias mykey-2 -storepass MY_PASSWORD -keypass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=TEST-2, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:test-2.com,ip:127.0.0.1
        keytool -importkeystore -srckeystore jks-keystore-temp-2.jks -destkeystore jks-keystore-combine.jks -srcalias mykey-2 -destalias mykey-2 -srcstorepass MY_PASSWORD -deststorepass MY_PASSWORD
        */
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withKeystoreType("JKS")
                .withKeystorePassword("MY_PASSWORD")
                .withKeyPassword("MY_PASSWORD")
                .withKeystoreFromClasspath("/jks-keystore-combine.jks"))
            .addHandler((request, response) -> {
                response.write("This is encrypted");
                return true;
            })
            .start();

        try (Response resp = call(request(server.httpsUri()))) {
            assertThat(resp.body().string(), equalTo("This is encrypted"));
        }

        assertThat(certInformationBySni(server.uri(), "localhost"), containsString("TEST-1"));
        assertThat(certInformationBySni(server.uri(), "test-1.com"), containsString("TEST-1"));
        assertThat(certInformationBySni(server.uri(), "test-2.com"), containsString("TEST-2"));
        assertThat(certInformationBySni(server.uri(), "not-matching"), containsString("TEST-2")); // TEST-2 is defaultAlias
    }

    private String certInformationBySni(URI uri, String sni) throws IOException {
        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());
        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setServerNames(Collections.singletonList(new SNIHostName(sni)));
        HttpsURLConnection.setDefaultSSLSocketFactory(new SSLSocketFactoryWrapper(sslContext.getSocketFactory(), sslParameters));
        HttpsURLConnection conn = (HttpsURLConnection) uri.toURL().openConnection();
        conn.setHostnameVerifier((hostname, session) -> true); // disable the client side handshake verification
        conn.connect();
        Certificate[] certs = conn.getServerCertificates();
        StringBuilder sb = new StringBuilder();
        for (Certificate cert : certs) {
            sb.append("Certificate is: ").append(cert);
            if (cert instanceof X509Certificate) {
                X509Certificate x = (X509Certificate) cert;
                sb.append(x.getIssuerDN());
            }
        }
        return sb.toString();
    }

    @Test
    public void certsCanBeChangedWhileRunning() throws Exception {

        /*
        Note: certs generated with:
        keytool -genkeypair -keystore jks-keystore.jks -storetype JKS -storepass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=My JKS Certificate, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:localhost,ip:127.0.0.1
        keytool -genkeypair -keystore pkcs12-keystore.p12 -storetype PKCS12 -storepass MY_PASSWORD -keyalg RSA -keysize 2048 -validity 999999 -dname "CN=My PKCS12 Certificate, OU=Ronin, O=MuServer, L=NA, ST=NA, C=NA" -ext san=dns:localhost,ip:127.0.0.1
         */

        HttpsConfigBuilder originalCert = HttpsConfigBuilder.httpsConfig()
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
        assertThat(server.sslInfo().certificates().stream().map(cert -> cert.getIssuerX500Principal().getName()).collect(Collectors.joining()),
            equalTo("CN=My JKS Certificate,OU=Ronin,O=MuServer,L=NA,ST=NA,C=NA"));


        HttpsConfigBuilder newCert = HttpsConfigBuilder.httpsConfig()
            .withKeystoreType("PKCS12")
            .withKeystorePassword("MY_PASSWORD")
            .withKeyPassword("MY_PASSWORD")
            .withKeystoreFromClasspath("/pkcs12-keystore.p12");

        server.changeHttpsConfig(newCert);
        assertThat(server.sslInfo().certificates().stream().map(cert -> cert.getIssuerX500Principal().getName()).collect(Collectors.joining()),
            equalTo("CN=My PKCS12 Certificate,OU=Ronin,O=MuServer,L=NA,ST=NA,C=NA"));

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
