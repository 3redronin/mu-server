package io.muserver;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.security.cert.X509Certificate;
import java.util.List;

public class HttpsConfig implements SSLInfo {
    private final SSLContext sslContext;
    private final SSLParameters sslParameters;
    private List<X509Certificate> certificates;

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
    public List<X509Certificate> certificates() {
        return certificates;
    }

    String[] cipherSuitesArray() {
        return sslParameters.getCipherSuites();
    }


    void setCertificates(List<X509Certificate> certificates) {
        this.certificates = certificates;
    }

}
