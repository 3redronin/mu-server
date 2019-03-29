package io.muserver;

import java.util.List;

class SSLInfoImpl implements SSLInfo {
    private final String providerName;
    private final List<String> protocols;
    private final List<String> ciphers;

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
    public String toString() {
        return "SSLInfoImpl{" +
            "providerName='" + providerName + '\'' +
            ", protocols=" + protocols +
            ", ciphers=" + ciphers +
            '}';
    }
}
