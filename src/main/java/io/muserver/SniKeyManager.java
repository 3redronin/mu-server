package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.*;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
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
        try { return chooseAlias(sslSocket.getHandshakeSession()); }
        catch (UnsupportedOperationException unsupported) { return defaultAlias; }
    }

    @Override
    public @Nullable String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        try {
            SSLSession session = engine.getHandshakeSession();
            // Session-ticket restoration can ask for a key before the new handshake session
            // exists. An engine's established session is safe to inspect without starting IO.
            return chooseAlias(session == null ? engine.getSession() : session);
        } catch (UnsupportedOperationException unsupported) { return defaultAlias; }
    }

    private @Nullable String chooseAlias(@Nullable SSLSession session) {
        String sniHostname = session == null ? null : SocketConnectionTransport.requestedServerName(session);
        if (sniHostname == null) return defaultAlias;
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
