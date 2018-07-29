package scaffolding;

import io.netty.util.ResourceLeakDetector;
import okhttp3.*;
import okio.BufferedSink;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientUtils {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ClientUtils.class);

    public static final OkHttpClient client;
    public static X509TrustManager veryTrustingTrustManager = veryTrustingTrustManager();

    static {
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
        client = newClient().build();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    public static OkHttpClient.Builder newClient() {
        boolean isDebug = ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("jdwp");
        return new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .hostnameVerifier((hostname, session) -> true)
            .readTimeout(isDebug ? 180 : 20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager);
    }

    public static RequestBody largeRequestBody(StringBuffer sentData) {
        return new RequestBody() {
            public MediaType contentType() {
                return MediaType.parse("text/plain");
            }

            public void writeTo(BufferedSink sink) throws IOException {
                write(sink, "Numbers\n");
                write(sink, "-------\n");
                for (int i = 2; i <= 997; i++) {
                    write(sink, String.format(" * %s\n", i));
                }
            }

            private void write(BufferedSink sink, String s) throws IOException {
                sentData.append(s);
                sink.writeUtf8(s);
            }
        };
    }

    public static Request.Builder request() {
        return new Request.Builder();
    }

    public static Response call(Request.Builder request) {
        for (int i = 0; i < 5; i++) {
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
