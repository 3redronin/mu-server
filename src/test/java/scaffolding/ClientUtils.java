package scaffolding;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientUtils {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ClientUtils.class);

    public static final OkHttpClient client;
    private static X509TrustManager veryTrustingTrustManager = veryTrustingTrustManager();

    static {
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
        boolean isDebug = ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("jdwp");
        client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .hostnameVerifier((hostname, session) -> true)
            .readTimeout(isDebug ? 180 : 20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager).build();
    }


    public static Request.Builder request() {
        return new Request.Builder();
    }
    public static Request.Builder request(URI uri) {
        return new Request.Builder().url(uri.toString());
    }

    public static Response call(Request.Builder request) {
        for (int i = 0; i < 1; i++) {
            Request req = request.build();
            try {
                return client.newCall(req).execute();
            } catch (SocketTimeoutException ste) {
                log.warn("Timeout... will let it retry", ste);
            } catch (IOException e) {
                throw new RuntimeException("Error while calling " + req, e);
            }
        }
        throw new RuntimeException("Timed out too many times");
    }

    public static SSLContext sslContextForTesting(TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException("Cannot set up test SSLContext", e);
        }
    }

    public static X509TrustManager veryTrustingTrustManager() {
        return new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[]{};
            }
        };
    }
}
