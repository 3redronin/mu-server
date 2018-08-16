package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class MuServerHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(MuServerHandler.class);
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private final List<MuHandler> handlers;
    private final MuStatsImpl stats;
    private final AtomicReference<MuServer> serverRef;
    private final String protocol;
    private final AtomicReference<AsyncContext> muContext = new AtomicReference<>();


    public MuServerHandler(List<MuHandler> handlers, MuStatsImpl stats, AtomicReference<MuServer> serverRef, String protocol) {
        this.handlers = handlers;
        this.stats = stats;
        this.serverRef = serverRef;
        this.protocol = protocol;
    }

    static void dealWithUnhandledException(MuRequest request, MuResponse response, Throwable ex) {
        if (response.hasStartedSendingData()) {
            log.warn("Unhandled error from handler for " + request + " (note that a " + response.status() +
                " was already sent to the client before the error occurred and so the client may receive an incomplete response)", ex);
        } else {
            String errorID = "ERR-" + UUID.randomUUID().toString();
            log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, ex);
            response.status(500);
            response.contentType(ContentTypes.TEXT_HTML);
            response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            response.write("<h1>500 Internal Server Error</h1><p>ErrorID=" + errorID + "</p>");
        }
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        AsyncContext state = muContext.get();
        super.channelInactive(ctx);
        if (state != null) {
            state.onDisconnected();
        }
    }

    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            onChannelRead(ctx, msg);
        } catch (Exception e) {
            log.info("Unhandled internal error", e);
            ctx.channel().close();
        }
    }

    private void onChannelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {

            HttpRequest request = (HttpRequest) msg;

            if (request.decoderResult().isFailure()) {
                stats.onInvalidRequest();
                handleHttpRequestDecodeFailure(ctx, request.decoderResult().cause());
            } else {
                if (HttpUtil.is100ContinueExpected(request)) {
                    int contentBody = request.headers().getInt(HeaderNames.CONTENT_LENGTH, -1);
                    if (contentBody < Integer.MAX_VALUE) {
                        // TODO reject if body size too large
                        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
                    } else {
                        sendSimpleResponse(ctx, "417 Expectation Failed", HttpResponseStatus.EXPECTATION_FAILED.code(), true);
                        return;
                    }
                }

                Method method;
                try {
                    method = Method.fromNetty(request.method());
                } catch (IllegalArgumentException e) {
                    sendSimpleResponse(ctx, "405 Method Not Allowed", 405, true);
                    return;
                }
                NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx.channel(), request, serverRef, method, protocol);
                stats.onRequestStarted(muRequest);

                AsyncContext asyncContext = new AsyncContext(muRequest, new NettyResponseAdaptor(ctx, muRequest), stats, new Runnable() {
                    @Override
                    public void run() {
                        ctx.channel().config().setAutoRead(true);
                    }
                });
                muRequest.nettyAsyncContext = asyncContext;
                this.muContext.set(asyncContext);

                Headers headers = muRequest.headers();
                if (headers.hasBody()) {
                    // There will be a request body, so set the streams
                    GrowableByteBufferInputStream requestBodyStream = new GrowableByteBufferInputStream();
                    muRequest.inputStream(requestBodyStream);
                    asyncContext.requestBody = requestBodyStream;
                }
                executor.execute(() -> {

                    boolean error = false;
                    MuResponse response = asyncContext.response;
                    try {

                        boolean handled = false;
                        for (MuHandler muHandler : handlers) {
                            handled = muHandler.handle(asyncContext.request, response);
                            if (handled) {
                                break;
                            }
                            if (muRequest.isAsync()) {
                                throw new IllegalStateException(muHandler.getClass() + " returned false however this is not allowed after starting to handle a request asynchronously.");
                            }
                        }
                        if (!handled) {
                            send404(asyncContext);
                        }


                    } catch (Throwable ex) {
                        error = true;
                        dealWithUnhandledException(muRequest, response, ex);
                    } finally {
                        if (error || !muRequest.isAsync()) {
                            try {
                                asyncContext.complete(error);
                            } catch (Throwable e) {
                                log.info("Error while completing request", e);
                            }
                        }
                    }
                });

            }

        } else if (msg instanceof HttpContent) {
            HttpContent content = (HttpContent) msg;
            AsyncContext state = this.muContext.get();
            if (state == null) {
                log.info("Got a chunk of message for an unknown request. This can happen when a request is rejected based on headers, and then the rejected body arrives.");
            } else {
                ByteBuf byteBuf = content.content();
                if (byteBuf.capacity() > 0) {
                    ByteBuf copy = byteBuf.copy();
                    ByteBuffer byteBuffer = ByteBuffer.allocate(byteBuf.capacity());
                    copy.readBytes(byteBuffer).release();
                    byteBuffer.flip();
                    state.requestBody.handOff(byteBuffer);
                }
                if (msg instanceof LastHttpContent) {
                    try {
                        GrowableByteBufferInputStream inputBuffer = state.requestBody;
                        if (inputBuffer != null) {
                            inputBuffer.close();
                        }
                    } catch (Exception e) {
                        log.info("Error while cleaning up request. It may mean the client did not receive the full response for " + state.request, e);
                    }
                }
            }
        }

        if (msg instanceof LastHttpContent) {
            ctx.channel().config().setAutoRead(false);
        }
    }

    static void send404(AsyncContext asyncContext) {
        MuResponse resp = asyncContext.response;
        resp.status(404);
        resp.contentType(ContentTypes.TEXT_PLAIN);
        resp.write("404 Not Found");
    }


    private static void handleHttpRequestDecodeFailure(ChannelHandlerContext ctx, Throwable cause) {
        String message = "Server error";
        int code = 500;
        if (cause instanceof TooLongFrameException) {
            if (cause.getMessage().contains("header is larger")) {
                code = 431;
                message = "HTTP headers too large";
            } else if (cause.getMessage().contains("line is larger")) {
                code = 414;
                message = "URI too long";
            }
        }
        sendSimpleResponse(ctx, message, code, true);
    }

    private static ChannelFuture sendSimpleResponse(ChannelHandlerContext ctx, String message, int code, boolean disconnect) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(code), copiedBuffer(message.getBytes(UTF_8)));
        response.headers().set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN);
        response.headers().set(HeaderNames.CONTENT_LENGTH, message.length());
        if (disconnect) {
            response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        }
        ChannelFuture future = ctx.writeAndFlush(response);
        if (disconnect) {
            future = future.addListener(ChannelFutureListener.CLOSE);
        }
        return future;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Unhandled exception", cause);
    }
}
