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

class SSLInfoImpl implements SSLInfo {
    private static final Logger log = LoggerFactory.getLogger(SSLInfoImpl.class);
    private final String providerName;
    private final List<String> protocols;
    private final List<String> ciphers;
    private volatile List<X509Certificate> cachedCerts = null;
    private volatile URI httpsUri;

    SSLInfoImpl(String providerName, List<String> protocols, List<String> ciphers) {
        this.providerName = providerName;
        this.protocols = protocols;
        this.ciphers = ciphers;
    }

    @Override
    public List<String> ciphers() {
        return ciphers;
    }

    @Override
    public List<String> protocols() {
        return protocols;
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public List<X509Certificate> certificates() {
        if (cachedCerts != null) {
            return cachedCerts;
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

    @Override
    public String toString() {
        return "SSLInfoImpl{" +
            "providerName='" + providerName + '\'' +
            ", protocols=" + protocols +
            ", ciphers=" + ciphers +
            '}';
    }

    void setHttpsUri(URI httpsUri) {
        this.httpsUri = httpsUri;
    }
}
