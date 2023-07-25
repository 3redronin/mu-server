package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;
import tlschannel.async.AsynchronousTlsChannel;
import tlschannel.async.AsynchronousTlsChannelGroup;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

class MuServer2 implements MuServer {
    private static final Charset utf8 = StandardCharsets.UTF_8;
    private static final Logger log = LoggerFactory.getLogger(MuServer2.class);
    private final ServerSocketChannel socketChannel;
    private final Thread accepterThread;

    public MuServer2(ServerSocketChannel socketChannel, Thread accepterThread) {
        this.socketChannel = socketChannel;
        this.accepterThread = accepterThread;
    }

    static MuServer start(MuServerBuilder builder) throws Exception {
        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.3");

        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();

        // connect server socket channel and register it in the selector
        ServerSocketChannel serverSocket = ServerSocketChannel.open();


        serverSocket.socket().bind(new InetSocketAddress(builder.httpsPort()));

        // accept raw connections normally
        log.info("Waiting for connection...");


        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean running = true;
                while (running) {
                    try {
                        SocketChannel rawChannel = serverSocket.accept();
                        rawChannel.configureBlocking(false);

                        // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal
                        // options
                        ServerTlsChannel.Builder builder = ServerTlsChannel.newBuilder(rawChannel, sslContext);

                        // instantiate TlsChannel
                        TlsChannel tlsChannel = builder.build();

                        // build asynchronous channel, based in the TLS channel and associated with the global
                        // group.
                        AsynchronousTlsChannel asyncTlsChannel =
                            new AsynchronousTlsChannel(channelGroup, tlsChannel, rawChannel);

                        var requestParser = new RequestParser(new RequestParser.Options(8192, 8192), new RequestParser.RequestListener() {
                            @Override
                            public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
                                log.info("Got " + method + " " + uri + " " + httpProtocolVersion + " with headers + " + headers);
                            }

                            @Override
                            public void onRequestComplete(MuHeaders trailers) {
                                log.info("Got trailers " + trailers);
                                asyncTlsChannel.write(Mutils.toByteBuffer("""
HTTP/1.1 200 OK
Date: Mon, 27 Jul 2009 12:28:53 GMT
Server: Apache/2.2.14 (Win32)
Last-Modified: Wed, 22 Jul 2009 19:15:56 GMT
Content-Length: 5
Content-Type: text/html
Connection: Closed

Hello""".replace("\n", "\r\n")));
                            }
                        });

                        // write to stdout all data sent by the client
                        ByteBuffer res = ByteBuffer.allocate(10000);
                        asyncTlsChannel.read(res, null, new CompletionHandler<Integer, Object>() {
                            @Override
                            public void completed(Integer result, Object attachment) {
                                if (result != -1) {
                                    res.flip();
                                    try {
                                        requestParser.offer(res);
                                    } catch (InvalidRequestException e) {
                                        log.error("Invalid request", e);
                                        throw new RuntimeException(e);
                                    }
                                    res.compact();
                                    // repeat
                                    asyncTlsChannel.read(res, null, this);
                                } else {
                                    try {
                                        asyncTlsChannel.close();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }

                            @Override
                            public void failed(Throwable exc, Object attachment) {
                                try {
                                    asyncTlsChannel.close();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                throw new RuntimeException(exc);
                            }
                        });
                    } catch (Exception e) {
                        if (Thread.interrupted()) {
                            log.info("Shutting down");
                            running = false;
                        } else {
                            log.error("Error in channel", e);
                        }
                    }
                }
            }
        }, "mu-acceptor");

        thread.start();

        return new MuServer2(serverSocket, thread);
    }

    @Override
    public void stop() {
        try {
            accepterThread.interrupt();
            socketChannel.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public URI uri() {
        return httpsUri();
    }

    @Override
    public URI httpUri() {
        return null;
    }

    @Override
    public URI httpsUri() {
        return URI.create("https://localhost:" + socketChannel.socket().getLocalPort());
    }

    @Override
    public MuStats stats() {
        return null;
    }

    @Override
    public Set<HttpConnection> activeConnections() {
        return null;
    }

    @Override
    public InetSocketAddress address() {
        return null;
    }

    @Override
    public long minimumGzipSize() {
        return 0;
    }

    @Override
    public int maxRequestHeadersSize() {
        return 0;
    }

    @Override
    public long requestIdleTimeoutMillis() {
        return 0;
    }

    @Override
    public long maxRequestSize() {
        return 0;
    }

    @Override
    public int maxUrlSize() {
        return 0;
    }

    @Override
    public boolean gzipEnabled() {
        return false;
    }

    @Override
    public Set<String> mimeTypesToGzip() {
        return null;
    }

    @Override
    public void changeHttpsConfig(HttpsConfigBuilder newHttpsConfig) {

    }

    @Override
    public SSLInfo sslInfo() {
        return null;
    }

    @Override
    public List<RateLimiter> rateLimiters() {
        return null;
    }
}
