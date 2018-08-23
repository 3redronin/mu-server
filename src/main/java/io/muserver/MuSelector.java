package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

class MuSelector {
    private static final Logger log = LoggerFactory.getLogger(MuSelector.class);

    InetSocketAddress address;

    public void start() throws IOException {
        AsynchronousServerSocketChannel listener
            = AsynchronousServerSocketChannel.open().bind(null);

        this.address = (InetSocketAddress) listener.getLocalAddress();

        log.info("Started at " + address.getPort());


        listener.accept(
            null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
                public void completed(AsynchronousSocketChannel client, Object attachment) {
                    if (listener.isOpen()) {
                        listener.accept(null, this);
                    }

                    System.out.println("Completed");
                    ByteBuffer buffer = ByteBuffer.allocate(8192);

                    RequestParser.RequestListener requestListener = new RequestParser.RequestListener() {
                        @Override
                        public void onHeaders(Method method, URI uri, HttpVersion httpVersion, MuHeaders requestHeaders, InputStream body) {
                            log.info(method + " " + uri + " " + httpVersion + " - " + requestHeaders);

                            boolean isKeepAlive = keepAlive(httpVersion, requestHeaders);

                            MuHeaders respHeaders = new MuHeaders();
                            respHeaders.set(HeaderNames.DATE.toString(), Mutils.toHttpDate(new Date()));
                            respHeaders.set("X-Blah", "Ah haha");

                            ResponseGenerator resp = new ResponseGenerator(httpVersion);
                            ByteBuffer toSend = resp.writeHeader(200, respHeaders);
                            client.write(toSend, 1, TimeUnit.MINUTES, null, new CompletionHandler<Integer, Object>() {
                                @Override
                                public void completed(Integer result, Object attachment) {
                                    log.info("Writing worked with " + result);
                                }

                                @Override
                                public void failed(Throwable exc, Object attachment) {
                                    log.error("Writing failed", exc);
                                }
                            });

                        }

                        @Override
                        public void onRequestComplete(MuHeaders trailers) {
                            log.info("Request complete. Trailers=" + trailers);
                        }
                    };
                    RequestParser requestParser = new RequestParser(requestListener);
                    readRequest(client, buffer, requestParser);


                }

                public void failed(Throwable exc, Object attachment) {
                    // handle failure
                    log.error("Failure", exc);
                }
            });
    }

    static boolean keepAlive(HttpVersion version, MuHeaders headers) {
        List<String> connection = headers.getAll("connection");
        switch (version) {
            case HTTP_1_1:
                for (String value : connection) {
                    String[] split = value.split(",\\s*");
                    for (String s : split) {
                        if (s.equalsIgnoreCase("close")) {
                            return false;
                        }
                    }
                }
                return true;
            case HTTP_1_0:
                for (String value : connection) {
                    String[] split = value.split(",\\s*");
                    for (String s : split) {
                        if (s.equalsIgnoreCase("keep-alive")) {
                            return true;
                        }
                    }
                }
                return false;
        }
        throw new IllegalArgumentException(version + " is not supported");
    }

    private static void readRequest(AsynchronousSocketChannel client, ByteBuffer buffer, RequestParser requestParser) {
        client.read(buffer, 1, TimeUnit.MINUTES, requestParser, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {
                RequestParser reqParser = (RequestParser)attachment;
                int read = result;
                if (read == -1) {
                    try {
                        log.info("Closing connection");
                        client.close();
                    } catch (IOException e) {
                        log.info("Error closing channel", e);
                    }
                    return;
                }
                log.info("Read " + read + " bytes with position " + buffer.position() + " and limit " + buffer.limit() + " and capacity " + buffer.capacity());

                buffer.limit(read);
                buffer.rewind();

                try {
                    reqParser.offer(buffer);
                    buffer.limit(8192);
                    buffer.rewind();
                    readRequest(client, buffer, requestParser);
                } catch (Exception e) {
                    log.error("Bad input", e);
                }
//                            for (int i = 0; i < result; i++) {
//                                byte b = buffer.get();
//
//                                log.info("Got " + b);
//                                buffer.limit(result);
//                                try {
//                                    CharBuffer decoded = decoder.decode(buffer);
//                                    decoded.toString()
//                                } catch (CharacterCodingException e) {
//                                    e.printStackTrace();
//                                }
//                            }
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                log.error("Failed to read", exc);
            }
        });
    }

}
