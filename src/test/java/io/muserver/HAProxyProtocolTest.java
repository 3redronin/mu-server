package io.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.ServerUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static scaffolding.ClientUtils.*;

public class HAProxyProtocolTest {

    private MuServer server;

    @Test
    public void ifEnabledButNotSentThereIsAnException() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withHttpsPort(-1)
            .withHttpPort(0)
            .withHAProxyProtocolEnabled(true)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("original IP: ");
            })
            .start();
        OkHttpClient client = ClientUtils.client.newBuilder()
            .addNetworkInterceptor(chain -> chain.proceed(chain.request()))
            .build();
        assertThrows(Exception.class, () -> call(client, request(server.uri())).close());
    }

    @Test
    public void clientConfigCanBeGottenFromHAProxyProtocolV1OverHttps() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withHAProxyProtocolEnabled(true)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                ProxiedConnectionInfo pi = request.connection().proxyInfo().get();
                response.sendChunk("Source: " + pi.sourceAddress() + ":" + pi.sourcePort());
                response.sendChunk(" Destination: " + pi.destinationAddress() + ":" + pi.destinationPort());
            })
            .start();

        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());
        HAProxySSLSocketFactory sslSocketFactory = new HAProxySSLSocketFactory(sslContext.getSocketFactory(), "PROXY TCP4 192.0.2.0 192.0.2.1 20567 443\r\n".getBytes(StandardCharsets.US_ASCII));

        OkHttpClient client = ClientUtils.client.newBuilder()
            .sslSocketFactory(sslSocketFactory, veryTrustingTrustManager())
            .build();
        try (Response resp = call(client, request(server.uri()))) {
            assertThat(resp.body().string(), is("Source: 192.0.2.0:20567 Destination: 192.0.2.1:443"));
        }
    }

    @Test
    public void clientConfigCanBeGottenFromHAProxyProtocolV2OverHttps() throws IOException {
        server = ServerUtils.httpsServerForTest()
            .withHAProxyProtocolEnabled(true)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                ProxiedConnectionInfo pi = request.connection().proxyInfo().get();
                response.sendChunk("Source: " + pi.sourceAddress() + ":" + pi.sourcePort());
                response.sendChunk(" Destination: " + pi.destinationAddress() + ":" + pi.destinationPort());
            })
            .start();

        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());

        byte[] signature = "\r\n\r\n\0\r\nQUIT\n".getBytes(StandardCharsets.US_ASCII);
        byte protocolVersionCommand = 0x21; // Version 2, PROXY command
        byte addressFamilyTransport = 0x11; // IPv4, STREAM
        short length = 12; // 12 bytes for IPv4 addresses and ports

        // IPv4 addresses and ports
        byte[] sourceAddress = new byte[]{(byte) 192, 0, 2, 0}; // 192.0.2.0
        byte[] destinationAddress = new byte[]{(byte) 192, 0, 2, 1}; // 192.0.2.1
        short sourcePort = (short) 20567; // Port 20567
        short destinationPort = (short) 443; // Port 443

        // Create buffer for full message
        ByteBuffer buffer = ByteBuffer.allocate(16 + length);
        buffer.put(signature); // 12 bytes signature
        buffer.put(protocolVersionCommand); // 1 byte version and command
        buffer.put(addressFamilyTransport); // 1 byte family and transport
        buffer.putShort(length); // 2 bytes length
        buffer.put(sourceAddress); // 4 bytes source address
        buffer.put(destinationAddress); // 4 bytes destination address
        buffer.putShort(sourcePort); // 2 bytes source port
        buffer.putShort(destinationPort); // 2 bytes destination port

        byte[] haproxyMessage = buffer.array();

        HAProxySSLSocketFactory sslSocketFactory = new HAProxySSLSocketFactory(sslContext.getSocketFactory(), haproxyMessage);

        OkHttpClient client = ClientUtils.client.newBuilder()
            .sslSocketFactory(sslSocketFactory, veryTrustingTrustManager())
            .build();
        try (Response resp = call(client, request(server.uri()))) {
            assertThat(resp.body().string(), is("Source: 192.0.2.0:20567 Destination: 192.0.2.1:443"));
        }
    }


    @Test
    public void clientConfigCanBeGottenFromHAProxyProtocolV1OverPlaintext() throws IOException {
        server = MuServerBuilder.httpServer()
            .withHAProxyProtocolEnabled(true)
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                ProxiedConnectionInfo pi = request.connection().proxyInfo().get();
                response.sendChunk("Source: " + pi.sourceAddress() + ":" + pi.sourcePort());
                response.sendChunk(" Destination: " + pi.destinationAddress() + ":" + pi.destinationPort());
            })
            .start();

        OkHttpClient client = ClientUtils.client.newBuilder()
            .addNetworkInterceptor(chain -> {
                chain.connection().socket().getOutputStream().write("PROXY TCP4 192.0.2.0 192.0.2.1 20567 443\r\n".getBytes(StandardCharsets.US_ASCII));
                return chain.proceed(chain.request());
            })
            .build();
        try (Response resp = call(client, request(server.uri()))) {
            assertThat(resp.body().string(), is("Source: 192.0.2.0:20567 Destination: 192.0.2.1:443"));
        }
    }



    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }



    private static class HAProxySSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final byte[] proxyHeader;

        public HAProxySSLSocketFactory(SSLSocketFactory delegate, byte[] proxyHeader) {
            this.delegate = delegate;
            this.proxyHeader = proxyHeader;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }


        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            writeProxyHeader(s);
            return delegate.createSocket(s, host, port, autoClose);
        }

        private void writeProxyHeader(Socket s) throws IOException {
            OutputStream os = s.getOutputStream();
            os.write(proxyHeader);
            os.flush();
        }

        @Override
        public Socket createSocket(Socket s, InputStream consumed, boolean autoClose) throws IOException {
            writeProxyHeader(s);
            return delegate.createSocket(s, consumed, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            throw new UnsupportedOperationException("Need a base socket to write on");
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            throw new UnsupportedOperationException("Need a base socket to write on");
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
            throw new UnsupportedOperationException("Need a base socket to write on");
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            throw new UnsupportedOperationException("Need a base socket to write on");
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            throw new UnsupportedOperationException("Need a base socket to write on");
        }
    }


}