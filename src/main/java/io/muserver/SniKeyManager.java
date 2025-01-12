package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Map;

class SniKeyManager extends X509ExtendedKeyManager {
    // with thanks to https://github.com/grahamedgecombe/netty-sni-example

    private final X509ExtendedKeyManager keyManager;
    private final @Nullable String defaultAlias;
    private final Map<String, String> sanToAliasMap;

    public SniKeyManager(X509ExtendedKeyManager keyManager, @Nullable String defaultAlias, Map<String, String> sanToAliasMap) {
        this.keyManager = keyManager;
        this.defaultAlias = defaultAlias;
        this.sanToAliasMap = sanToAliasMap;
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        throw new UnsupportedOperationException("Not a client");
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        throw new UnsupportedOperationException("Not a client");
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        throw new UnsupportedOperationException("Not a client");
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return keyManager.getServerAliases(keyType, issuers);
    }

    @Override
    public @Nullable String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        var sslSocket = (SSLSocket) socket;
        var session = (ExtendedSSLSession)sslSocket.getHandshakeSession(); // Get the current handshake session
        return chooseAlias(session.getRequestedServerNames());
    }

    @Override
    public @Nullable String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        ExtendedSSLSession session = (ExtendedSSLSession) engine.getHandshakeSession();
        return chooseAlias(session.getRequestedServerNames());
    }

    private @Nullable String chooseAlias(Collection<SNIServerName> requestedServerNames) {
        // Pick first SNIHostName in the list of SNI names.
        String sniHostname = null;
        for (SNIServerName name : requestedServerNames) {
            if (name.getType() == StandardConstants.SNI_HOST_NAME) {
                sniHostname = ((SNIHostName) name).getAsciiName();
                break;
            }
        }

        String hostname = sanToAliasMap.get(sniHostname);
        if (hostname == null) {
            hostname = sniHostname;
        }

        // If we got given a hostname over SNI, check if we have a cert and key for that hostname. If so, we use it.
        // Otherwise, we fall back to the default certificate.
        if (hostname != null && (getCertificateChain(hostname) != null && getPrivateKey(hostname) != null))
            return hostname;
        else
            return defaultAlias;
    }

    @Override
    public X509Certificate@Nullable[] getCertificateChain(String alias) {
        return keyManager.getCertificateChain(alias);
    }

    @Override
    public @Nullable PrivateKey getPrivateKey(String alias) {
        return keyManager.getPrivateKey(alias);
    }
}
