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
    private final ClientCertificateAuthentication clientCertificateAuthentication;

    @Nullable
    private final X509TrustManager clientAuthTrustManager;
    @Nullable
    private List<X509Certificate> cachedCerts;
    @Nullable
    private URI httpsUri;

    HttpsConfig(SSLContext sslContext, SSLParameters sslParameters,
                ClientCertificateAuthentication clientCertificateAuthentication,
                @Nullable X509TrustManager clientAuthTrustManager) {
        this.sslContext = sslContext;
        this.sslParameters = sslParameters;
        this.clientCertificateAuthentication = clientCertificateAuthentication;
        this.clientAuthTrustManager = clientAuthTrustManager;
    }

    /**
     * Gets the SSL context used by the HTTPS listener.
     *
     * @return The active SSL context.
     */
    public SSLContext sslContext() {
        return sslContext;
    }

    /**
     * Gets the SSL parameters applied to accepted HTTPS connections.
     *
     * @return The configured SSL parameters.
     */
    public SSLParameters sslParameters() { return sslParameters; }

    String[] protocolsArray() {
        return sslParameters.getProtocols();
    }

    /**
     * Gets the enabled cipher suites in server preference order.
     *
     * @return An unmodifiable list of enabled cipher suite names.
     */
    @Override
    public List<String> ciphers() {
        String[] cs = cipherSuitesArray();
        return cs == null ? Collections.emptyList() : List.of(cs);
    }

    /**
     * Gets the enabled TLS protocol versions.
     *
     * @return An unmodifiable list of enabled protocols, such as <code>TLSv1.2</code>.
     */
    @Override
    public List<String> protocols() {
        return List.of(protocolsArray());
    }

    /**
     * Gets the name of the SSL provider backing this HTTPS configuration.
     *
     * @return The SSL provider name, for example <code>SunJSSE</code>.
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
                    @Override
                    public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
                    }
                    @Override
                    public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
                    }
                    @Override
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
     * Gets the configured client-certificate authentication policy.
     *
     * @return The client-certificate authentication policy.
     */
    public ClientCertificateAuthentication clientCertificateAuthentication() {
        return clientCertificateAuthentication;
    }

    /**
     * Gets the explicitly configured trust manager used to validate client certificates.
     *
     * @return The trust manager for client-certificate validation, or <code>null</code> if client certificates
     * are not requested or validation is delegated to a pre-built SSL context.
     */
    public @Nullable X509TrustManager clientAuthTrustManager() {
        return clientAuthTrustManager;
    }
}
