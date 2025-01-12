package io.muserver;

import org.jspecify.annotations.Nullable;
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

/**
 * HTTPS configuration
 */
public class HttpsConfig implements SSLInfo {
    private static final Logger log = LoggerFactory.getLogger(HttpsConfig.class);
    private final SSLContext sslContext;
    private final SSLParameters sslParameters;

    @Nullable
    private final X509TrustManager clientAuthTrustManager;
    @Nullable
    private List<X509Certificate> cachedCerts;
    @Nullable
    private URI httpsUri;

    HttpsConfig(SSLContext sslContext, SSLParameters sslParameters, @Nullable X509TrustManager clientAuthTrustManager) {
        this.sslContext = sslContext;
        this.sslParameters = sslParameters;
        this.clientAuthTrustManager = clientAuthTrustManager;
    }

    /**
     * @return The SSLContext
     */
    public SSLContext sslContext() {
        return sslContext;
    }

    /**
     * @return The SSL parameters
     */
    public SSLParameters sslParameters() { return sslParameters; }

    String[] protocolsArray() {
        return sslParameters.getProtocols();
    }

    /**
     * @return An unmodifiable list of ciphers supported, in preference order
     */
    @Override
    public List<String> ciphers() {
        String[] cs = cipherSuitesArray();
        return cs == null ? Collections.emptyList() : List.of(cs);
    }

    /**
     * @return An unmodifiable list of protocols supported, such as <code>TLSv1.2</code>
     */
    public List<String> protocols() {
        return List.of(protocolsArray());
    }

    /**
     * @return Gets the SSL provider, e.g. <code>SunJSSE</code>
     */
    @Override
    public String providerName() {
        return sslContext.getProvider().getName();
    }

    /**
     * <p>Gets the server certificates that are in use.</p>
     * <p>Note: The certificate information is found by making an HTTPS connection to
     * <code>https://localhost:{port}/</code> and if any exceptions are thrown while
     * doing the lookup then an empty array is returned.</p>
     * <p>Using this information, you can find information such as the expiry date of your
     * certiticates by calling {@link X509Certificate#getNotAfter()}.</p>
     * @return An ordered list of server certificates, with the server's own certificate first followed by any certificate authorities.
     */
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
                    public X509Certificate@Nullable[] getAcceptedIssuers() {
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

    String@Nullable[] cipherSuitesArray() {
        return sslParameters.getCipherSuites();
    }

    void setHttpsUri(URI httpsUri) {
        this.httpsUri = httpsUri;
    }

    /**
     * @return The trust manager to verify client certs when client certs are requested, or <code>null</code>
     *         if client certs not used.
     */
    public @Nullable X509TrustManager clientAuthTrustManager() {
        return clientAuthTrustManager;
    }
}
