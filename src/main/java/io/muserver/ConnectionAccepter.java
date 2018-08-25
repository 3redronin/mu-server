package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ConnectionAccepter {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAccepter.class);

    InetSocketAddress address;
    private Thread thread;
    private final CountDownLatch startLatch = new CountDownLatch(1);
    private volatile boolean running = false;
    private Selector selector;
    private final SSLContext sslContext;
    private final AtomicReference<MuServer> serverRef;

    public ConnectionAccepter(SSLContext sslContext, AtomicReference<MuServer> serverRef) {
        this.sslContext = sslContext;
        this.serverRef = serverRef;
    }


    public void start() throws Exception {
        long start = System.currentTimeMillis();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    startIt();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "ConnectionAcceptor");
        running = true;
        thread.start();
        boolean started = startLatch.await(30, TimeUnit.SECONDS);
        log.info("Started? " + started + " in " + (System.currentTimeMillis() - start) + "ms");
    }

    private void startIt() throws Exception {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.socket().bind(new InetSocketAddress(0));
            serverSocket.configureBlocking(false);
            this.selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            this.address = (InetSocketAddress) serverSocket.getLocalAddress();

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

                        ClientConnection cc;
                        if (sslContext == null) {
                            cc = new ClientConnection(rawChannel, "http", clientAddress, serverRef.get());
                        } else {
                            TlsChannel tlsChannel = ServerTlsChannel.newBuilder(rawChannel, sslContext).build();
                            cc = new ClientConnection(tlsChannel, "https", clientAddress, serverRef.get());
                        }
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
            selector.wakeup();
            running = false;
            thread.join();
        }
    }

}
