package validation;

import java.net.URI;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

/** Independent interoperability smoke clients; certificate validation is tested separately. */
public final class IndependentClient {
    public static void main(String[] args) throws Exception {
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] certificates, String type) {}
            public void checkServerTrusted(X509Certificate[] certificates, String type) {}
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
        if (args[0].equals("jdk")) {
            var client = java.net.http.HttpClient.newBuilder().sslContext(context).connectTimeout(java.time.Duration.ofSeconds(5)).build();
            var response = client.send(java.net.http.HttpRequest.newBuilder(URI.create(args[1])).timeout(java.time.Duration.ofSeconds(5)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !response.body().equals("hello") || response.version() != java.net.http.HttpClient.Version.HTTP_2)
                throw new AssertionError("JDK response/status/protocol mismatch");
            System.out.println("OK HTTP_2");
        } else {
            var client = new okhttp3.OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(), trust).build();
            try (var response = client.newCall(new okhttp3.Request.Builder().url(args[1]).build()).execute()) {
                if (response.code() != 200 || !response.body().string().equals("hello") || response.protocol() != okhttp3.Protocol.HTTP_2)
                    throw new AssertionError("OkHttp response/status/protocol mismatch");
                System.out.println("OK HTTP_2");
            } finally { client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll(); }
        }
    }
}
