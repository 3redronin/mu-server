package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.codec.http2.Http2Exception;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;

interface FormRequestBodyReader {
    RequestParameters params();

    List<UploadedFile> uploads(String name);
}

abstract class RequestBodyReader {

    private final CompletableFuture<Throwable> future = new CompletableFuture<>();
    private final AtomicLong bytes = new AtomicLong();
    final long maxSize;

    long receivedBytes() {
        return bytes.get();
    }

    protected RequestBodyReader(long maxSize) {
        this.maxSize = maxSize;
    }

    boolean completed() {
        return future.isDone();
    }

    protected Throwable currentError() {
        return future.getNow(null);
    }

    void onCancelled(Throwable cause) {
        future.complete(cause);
    }

    final void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
        try {
            long soFar = bytes.addAndGet(content.readableBytes());
            if (soFar > maxSize) {
                throw new ClientErrorException(closingResponse(413, "The request body was too large"));
            } else {
                onRequestBodyRead0(content, last, error -> {
                    if (error != null) {
                        future.complete(error);
                    } else if (last) {
                        future.complete(null);
                    }
                    callback.onComplete(error);
                });
            }
        } catch (Exception e) {
            try {
                callback.onComplete(e);
                future.complete(e);
            } catch (Exception ignored) {
            }
        }
    }

    abstract protected void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback);

    /**
     * Blocks until the body is fully read, or throws an exception. Converts things like timeouts to request timeout client exceptions.
     */
    void blockUntilFullyRead() throws IOException {
        Throwable throwable;
        try {
            throwable = future.get(1, TimeUnit.HOURS); // TODO: configure this. Note max-upload-size + read-idle timeouts are applying too.
            if (throwable instanceof Http2Exception.StreamException) {
                throwable = throwable.getCause();
            }
            if (throwable instanceof TimeoutException) {
                throw new ClientErrorException(closingResponse(408, "Idle time out reading request body"));
            } else if (throwable instanceof WebApplicationException) {
                throw (WebApplicationException) throwable;
            }
        } catch (ExecutionException e) {
            throwable = Mutils.coalesce(e.getCause(), e);
        } catch (TimeoutException e) {
            throw new IOException("Timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while reading request body");
        }
        if (throwable != null) {
            if(throwable instanceof IOException) {
                throw (IOException) throwable;
            } else if (throwable instanceof WebApplicationException) {
                throw (WebApplicationException) throwable;
            } else {
                throw new IOException("Error while reading body", throwable);
            }
        }
    }

    private static Response closingResponse(int status, String message) {
        return Response.status(status).entity(message)
            .header(HeaderNames.CONNECTION.toString(), HeaderValues.CLOSE)
            .build();
    }

    public void cleanup() {}

    static class ListenerAdapter extends RequestBodyReader {
        private final NettyRequestAdapter.AsyncHandleImpl asyncHandle;
        private final RequestBodyListener readListener;

        public ListenerAdapter(NettyRequestAdapter.AsyncHandleImpl asyncHandle, long maxSize, RequestBodyListener readListener) {
            super(maxSize);
            this.asyncHandle = asyncHandle;
            this.readListener = readListener;
        }

        @Override
        public void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                if (content.readableBytes() > 0) {
                    DoneCallback successCalled = !last ? callback : error -> {
                        if (error == null) {
                            readListener.onComplete();
                        } else {
                            readListener.onError(error);
                        }
                        callback.onComplete(error);
                    };
                    readListener.onDataReceived(content.nioBuffer(), successCalled);
                } else if (last) {
                    readListener.onComplete();
                    callback.onComplete(null);
                }
            } catch (Exception e) {
                try {
                    callback.onComplete(e);
                } catch (Exception ignored) {
                } finally {
                    readListener.onError(e);
                }
            }
        }

        @Override
        void onCancelled(Throwable cause) {
            super.onCancelled(cause);
            readListener.onError(cause);
            asyncHandle.complete(cause);
        }
    }

    static class DiscardingReader extends RequestBodyReader {

        DiscardingReader(long maxSize) {
            super(maxSize);
        }

        @Override
        public void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                callback.onComplete(null);
            } catch (Exception ignored) {
            }
        }
    }

    static class UrlEncodedBodyReader extends RequestBodyReader implements FormRequestBodyReader {
        private final StringRequestBodyReader stringReader;
        private RequestParameters form;

        public UrlEncodedBodyReader(StringRequestBodyReader stringReader) {
            super(stringReader.maxSize);
            this.stringReader = stringReader;
        }

        @Override
        public List<UploadedFile> uploads(String name) {
            return emptyList();
        }

        @Override
        public RequestParameters params() {
            return form;
        }


        @Override
        public void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback) {
            stringReader.onRequestBodyRead(content, last, error -> {
                if (error == null && last) {
                    QueryStringDecoder decoder = new QueryStringDecoder(stringReader.body(), UTF_8, false, 1000000);
                    form = new NettyRequestParameters(decoder.parameters());
                }
                callback.onComplete(error);
            });

        }

    }

    static class MultipartFormReader extends RequestBodyReader implements FormRequestBodyReader {
        private static final Logger log = LoggerFactory.getLogger(MultipartFormReader.class);
        private final HttpPostMultipartRequestDecoder multipartRequestDecoder;
        private RequestParameters form;
        private final HashMap<String, List<UploadedFile>> uploads = new HashMap<>();

        @Override
        public RequestParameters params() {
            return form;
        }

        public MultipartFormReader(long maxSize, HttpRequest nettyRequest, Charset charset) {
            super(maxSize);
            HttpDataFactory factory = new DefaultHttpDataFactory(charset);
            multipartRequestDecoder = new HttpPostMultipartRequestDecoder(factory, nettyRequest, charset);
        }

        @Override
        public void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback) {
            multipartRequestDecoder.offer(new DefaultHttpContent(content));
            if (last) {
                multipartRequestDecoder.offer(new DefaultLastHttpContent());

                List<InterfaceHttpData> bodyHttpDatas = multipartRequestDecoder.getBodyHttpDatas();
                Map<String, List<String>> parameters = new HashMap<>();

                for (InterfaceHttpData bodyHttpData : bodyHttpDatas) {
                    if (bodyHttpData instanceof FileUpload) {
                        FileUpload fileUpload = (FileUpload) bodyHttpData;
                        if (fileUpload.length() == 0 && Mutils.nullOrEmpty(fileUpload.getFilename())) {
                            // nothing uploaded
                        } else {
                            UploadedFile uploadedFile = new MuUploadedFile(fileUpload);
                            addFile(fileUpload.getName(), uploadedFile);
                        }
                    } else if (bodyHttpData instanceof Attribute) {
                        Attribute a = (Attribute) bodyHttpData;
                        try {
                            String name = a.getName();
                            List<String> values = parameters.computeIfAbsent(name, k -> new LinkedList<>());
                            values.add(a.getValue());
                        } catch (IOException e) {
                            throw new UncheckedIOException("Error reading form parameter", e);
                        }
                    } else {
                        log.warn("Unrecognised body part: " + bodyHttpData.getClass() + " from " + this + " - this may mean some of the request data is lost.");
                    }
                }
                form = new NettyRequestParameters(parameters);
            }
            try {
                callback.onComplete(null);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void cleanup() {
            super.cleanup();
            multipartRequestDecoder.destroy();
        }

        private void addFile(String name, UploadedFile file) {
            if (!uploads.containsKey(name)) {
                uploads.put(name, new ArrayList<>());
            }
            uploads.get(name).add(file);
        }

        @Override
        public List<UploadedFile> uploads(String name) {
            List<UploadedFile> list = uploads.get(name);
            return list == null ? emptyList() : list;
        }

    }

    static class StringRequestBodyReader extends RequestBodyReader {
        private final Charset bodyCharset;
        private final CompositeByteBuf list = Unpooled.compositeBuffer();
        private volatile String result;

        public StringRequestBodyReader(long maxSize, Charset bodyCharset) {
            super(maxSize);
            this.bodyCharset = bodyCharset;
        }

        @Override
        public void onRequestBodyRead0(ByteBuf content, boolean last, DoneCallback callback) {
            try {
                if (content.readableBytes() > 0) {
                    list.addComponent(true, content.retain());
                }
                if (last) {
                    result = list.toString(bodyCharset);
                    list.release();
                }
                callback.onComplete(null);
            } catch (Exception e) {
                try {
                    callback.onComplete(e);
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        void onCancelled(Throwable cause) {
            super.onCancelled(cause);
            list.release();
        }

        @Override
        public void cleanup() {
            super.cleanup();
            result = null;
        }

        public String body() {
            if (result == null) {
                throw new IllegalStateException("Can only read the body after the entire body is read and before the request is completed");
            }
            return result;
        }
    }
}
