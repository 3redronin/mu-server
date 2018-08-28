package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;
import tlschannel.ServerTlsChannel;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ConnectionAcceptor {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);
    private final ExecutorService executorService;

    InetSocketAddress address;
    private Thread thread;
    private final CountDownLatch startLatch = new CountDownLatch(1);
    private volatile boolean running = false;
    private Selector selector;
    private final List<MuHandler> handlers;
    private final SSLContext sslContext;
    private final AtomicReference<MuServer> serverRef;
    private URI uri;
    private final String protocol;
    private final RequestParser.Options parserOptions;

    ConnectionAcceptor(ExecutorService executorService, List<MuHandler> handlers, SSLContext sslContext, AtomicReference<MuServer> serverRef, RequestParser.Options parserOptions) {
        this.executorService = executorService;
        this.handlers = handlers;
        this.sslContext = sslContext;
        this.serverRef = serverRef;
        this.protocol = sslContext == null ? "http" : "https";
        this.parserOptions = parserOptions;
    }


    public void start(String host, int port) throws Exception {
        long start = System.currentTimeMillis();
        thread = new Thread(() -> {
            try {
                startIt(host, port);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "ConnectionAcceptor");
        running = true;
        thread.start();
        boolean started = startLatch.await(30, TimeUnit.SECONDS);
        log.info("Started? " + started + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    private void startIt(String host, int port) throws Exception {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {

            InetSocketAddress endpoint = host == null
                ? new InetSocketAddress(port)
                : AccessController.doPrivileged((PrivilegedAction<InetSocketAddress>) () -> new InetSocketAddress(host, port));;
            serverSocket.socket().bind(endpoint);
            serverSocket.configureBlocking(false);
            this.selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            this.address = (InetSocketAddress) serverSocket.getLocalAddress();

            this.uri = new URI(sslContext == null ? "http" : "https", null, host == null ? "localhost" : host, address.getPort(), "/", null, null);

            startLatch.countDown();

            while (running) {
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isAcceptable()) {
                        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
                        SocketChannel rawChannel = serverChannel.accept();
                        rawChannel.configureBlocking(false);

                        InetAddress clientAddress = ((InetSocketAddress) rawChannel.getRemoteAddress()).getAddress();

                        ByteChannel byteChannel;
                        if (sslContext == null) {
                            byteChannel = rawChannel;
                        } else {
                            byteChannel = ServerTlsChannel.newBuilder(rawChannel, sslContext).build();
                        }
                        ClientConnection cc = new ClientConnection(executorService, handlers, byteChannel, protocol, clientAddress, serverRef.get(), parserOptions);
                        SelectionKey newKey = rawChannel.register(selector, SelectionKey.OP_READ);
                        newKey.attach(cc);

                    } else if (key.isReadable() || key.isWritable()) {
                        ClientConnection cc = (ClientConnection) key.attachment();
                        ByteBuffer buffer = ByteBuffer.allocate(10000);
                        try {
                            int c = cc.channel.read(buffer);
                            if (c > 0) {
                                buffer.flip();
                                cc.onBytesReceived(buffer);
                            } else if (c < 0) {
                                cc.onClientClosed();
                            }
                        } catch (NeedsReadException e) {
                            key.interestOps(SelectionKey.OP_READ); // overwrites previous value
                        } catch (NeedsWriteException e) {
                            key.interestOps(SelectionKey.OP_WRITE); // overwrites previous value
                        }
                    } else {
                        log.info("Got selector key " + key);
                    }
                }
            }
        }
    }


    public void stop() throws InterruptedException {
        if (running) {
            running = false;
            selector.wakeup();
            thread.join();
        }
    }

    @Override
    public String toString() {
        return "Connection[" + uri + "]";
    }

    public URI uri() {
        return uri;
    }
}
