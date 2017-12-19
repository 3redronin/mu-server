package scaffolding;

import okhttp3.*;
import okio.BufferedSink;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class ClientUtils {

	public static final OkHttpClient client = new OkHttpClient.Builder()
        .sslSocketFactory(sslContextForTesting().getSocketFactory(), veryTrustingTrustManager())
        .build();

    private static SSLContext sslContextForTesting() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot set up test SSLContext", e);
        }
    }

    private static X509TrustManager veryTrustingTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[]{};
            }
        };
    }

    public static RequestBody largeRequestBody(StringBuffer sentData) throws IOException {
		return new RequestBody() {
			@Override
			public MediaType contentType() {
				return MediaType.parse("text/plain");
			}

			@Override
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
		try {
			return client.newCall(request.build()).execute();
		} catch (IOException e) {
			throw new RuntimeException("Error while calling " + request, e);
		}
	}
}
