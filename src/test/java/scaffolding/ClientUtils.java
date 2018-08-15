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
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
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
        Request req = request.build();
        try {
            return client.newCall(req).execute();
        } catch (IOException e) {
            throw new RuntimeException("Error while calling " + req, e);
        }
    }

    private static SSLContext sslContextForTesting(TrustManager trustManager) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{trustManager}, null);
            return context;
        } catch (Exception e) {
            throw new RuntimeException("Cannot set up test SSLContext", e);
        }
    }

    private static X509TrustManager veryTrustingTrustManager() {
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
