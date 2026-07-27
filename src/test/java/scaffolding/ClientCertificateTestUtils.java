package scaffolding;

import okhttp3.OkHttpClient;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientCertificateTestUtils {
    private ClientCertificateTestUtils() {
    }

    public static OkHttpClient clientWithCertificate(String certFilename) throws Exception {
        SSLContext sslContext = ClientUtils.getPKCS12Context(
            "/client-certs/" + certFilename, "export password");
        return new OkHttpClient.Builder()
            .sslSocketFactory(sslContext.getSocketFactory(), ClientUtils.veryTrustingTrustManager())
            .build();
    }

    public static OkHttpClient clientForcingCertificate(String certFilename) throws Exception {
        return clientForcingCertificate(certFilename, new AtomicBoolean());
    }

    public static OkHttpClient clientForcingCertificate(
        String certFilename, AtomicBoolean certificateWasRequested) throws Exception {
        char[] password = "export password".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = ClientCertificateTestUtils.class.getResourceAsStream(
            "/client-certs/" + certFilename)) {
            keyStore.load(inputStream, password);
        }
        String alias = keyStore.aliases().nextElement();
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        X509KeyManager delegate = null;
        for (KeyManager keyManager : factory.getKeyManagers()) {
            if (keyManager instanceof X509KeyManager) {
                delegate = (X509KeyManager) keyManager;
                break;
            }
        }
        if (delegate == null) {
            throw new IllegalStateException("No X509KeyManager was created");
        }
        X509KeyManager forcedKeyManager = new ForcedClientAliasKeyManager(
            delegate, alias, certificateWasRequested);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(new KeyManager[]{forcedKeyManager},
            new X509TrustManager[]{ClientUtils.veryTrustingTrustManager()}, null);
        return new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .sslSocketFactory(sslContext.getSocketFactory(), ClientUtils.veryTrustingTrustManager())
            .build();
    }

    private static class ForcedClientAliasKeyManager implements X509KeyManager {
        private final X509KeyManager delegate;
        private final String clientAlias;
        private final AtomicBoolean certificateWasRequested;

        private ForcedClientAliasKeyManager(X509KeyManager delegate, String clientAlias,
                                            AtomicBoolean certificateWasRequested) {
            this.delegate = delegate;
            this.clientAlias = clientAlias;
            this.certificateWasRequested = certificateWasRequested;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            certificateWasRequested.set(true);
            return clientAlias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
    }
}
