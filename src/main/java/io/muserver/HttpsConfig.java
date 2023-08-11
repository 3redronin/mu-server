package io.muserver;

import javax.net.ssl.SSLContext;
import java.util.List;

public class HttpsConfig {
    private final SSLContext sslContext;
    private final String[] protocols;
    private final String[] cipherSuites;

    public HttpsConfig(SSLContext sslContext, String[] protocols, String[] cipherSuites) {
        this.sslContext = sslContext;
        this.protocols = protocols;
        this.cipherSuites = cipherSuites;
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    String[] protocolsArray() {
        return protocols;
    }
    public List<String> protocols() {
        return List.of(protocols);
    }

    String[] cipherSuitesArray() {
        return cipherSuites;
    }
    public List<String> cipherSuites() {
        return List.of(cipherSuites);
    }
}
