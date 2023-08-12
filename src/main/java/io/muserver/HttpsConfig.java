package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HttpsConfig implements SSLInfo {
    private static final Logger log = LoggerFactory.getLogger(HttpsConfig.class);
    private final SSLContext sslContext;
    private final SSLParameters sslParameters;
    private List<X509Certificate> cachedCerts;
    private URI httpsUri;

    HttpsConfig(SSLContext sslContext, SSLParameters sslParameters) {
        this.sslContext = sslContext;
        this.sslParameters = sslParameters;
    }

    public SSLContext sslContext() {
        return sslContext;
    }
    public SSLParameters sslParameters() { return sslParameters; };

    String[] protocolsArray() {
        return sslParameters.getProtocols();
    }

    @Override
    public List<String> ciphers() {
        return List.of(cipherSuitesArray());
    }

    public List<String> protocols() {
        return List.of(protocolsArray());
    }

    @Override
    public String providerName() {
        return sslContext.getProvider().getName();
    }

    @Override
    public synchronized List<X509Certificate> certificates() {
        if (cachedCerts != null) {
            return cachedCerts;
        }
        if (httpsUri == null) {
            return Collections.emptyList();
        }
        HttpsURLConnection conn = null;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(new KeyManager[0], new TrustManager[] {new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
                    }
                    public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
                    }
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                }},
                new SecureRandom());
            conn = (HttpsURLConnection) httpsUri.toURL().openConnection();
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier((arg0, arg1) -> true);
            conn.setConnectTimeout(5000);
            conn.connect();
            List<X509Certificate> results = new ArrayList<>();
            Certificate[] certs = conn.getServerCertificates();
            for (Certificate cert :certs){
                if (cert instanceof X509Certificate) {
                    results.add((X509Certificate) cert);
                }
            }
            cachedCerts = results;
            return results;
        } catch (Exception e) {
            log.warn("Error finding SSL certificate info", e);
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    String[] cipherSuitesArray() {
        return sslParameters.getCipherSuites();
    }

    void setHttpsUri(URI httpsUri) {
        this.httpsUri = httpsUri;
    }

}
