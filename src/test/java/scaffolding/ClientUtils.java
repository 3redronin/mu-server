package scaffolding;

import io.netty.util.ResourceLeakDetector;
import okhttp3.*;
import okio.BufferedSink;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientUtils {

    public static final OkHttpClient client;
    private static final X509TrustManager veryTrustingTrustManager = veryTrustingTrustManager();
    private static volatile HttpClient jettyClient;

    static {
        System.setProperty("io.netty.leakDetection.targetRecords", "1000");
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
        boolean isDebug = ManagementFactory.getRuntimeMXBean().getInputArguments().toString().contains("jdwp");
        client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
//            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
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

    public static Request.Builder request(URI uri) {
        return request().url(uri.toString());
    }

    public static Response call(Request.Builder request) {
        Request req = request.build();
        try {
            return client.newCall(req).execute();
        } catch (IOException e) {
            throw new UncheckedIOException("Error while calling " + req, e);
        }
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

    public static boolean isHttp2(Response response) {
        return response.protocol().name().equalsIgnoreCase("HTTP_2");
    }

    public static synchronized HttpClient jettyClient() {
        if (jettyClient == null) {
            try {
                SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
                sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
                ClientConnector clientConnector = new ClientConnector();
                clientConnector.setSslContextFactory(sslContextFactory);
                jettyClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
                jettyClient.start();
            } catch (Exception e) {
                throw new RuntimeException("Couldn't start jetty client", e);
            }
        }
        return jettyClient;
    }
}
