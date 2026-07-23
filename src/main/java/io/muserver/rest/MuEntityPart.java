package io.muserver.rest;

import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collections;

final class MuEntityPart implements EntityPart {

    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private final String name;
    private final String fileName;
    private final MultivaluedMap<String, String> headers;
    private final InputStream content;
    private final EntityProviders entityProviders;
    private boolean contentClaimed;

    MuEntityPart(String name, String fileName, MultivaluedMap<String, String> headers, InputStream content,
                 EntityProviders entityProviders) {
        this.name = Objects.requireNonNull(name, "name");
        this.fileName = fileName;
        MultivaluedMap<String, String> copy = new LowercasedMultivaluedHashMap<>();
        headers.forEach((key, values) ->
            copy.put(key, Collections.unmodifiableList(new ArrayList<>(values))));
        this.headers = ReadOnlyMultivaluedMap.readOnly(copy);
        this.content = Objects.requireNonNull(content, "content");
        this.entityProviders = entityProviders;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Optional<String> getFileName() {
        return Optional.ofNullable(fileName);
    }

    @Override
    public synchronized InputStream getContent() {
        claimContent();
        return content;
    }

    @Override
    public <T> T getContent(Class<T> type) throws IOException, WebApplicationException {
        return readContent(type, type);
    }

    @Override
    public <T> T getContent(GenericType<T> type) throws IOException, WebApplicationException {
        Objects.requireNonNull(type, "type");
        return readContent(type.getRawType(), type.getType());
    }

    @SuppressWarnings("unchecked")
    private synchronized <T> T readContent(Class<?> rawType, Type genericType) throws IOException, WebApplicationException {
        Objects.requireNonNull(rawType, "type");
        claimContent();
        MediaType mediaType = getMediaType();
        try {
            MessageBodyReader<T> reader = (MessageBodyReader<T>) entityProviders.selectReader(
                rawType, genericType, NO_ANNOTATIONS, mediaType);
            try (InputStream in = content) {
                return reader.readFrom((Class<T>) rawType, genericType, NO_ANNOTATIONS, mediaType, headers, in);
            }
        } catch (NotSupportedException e) {
            throw new IllegalArgumentException("No entity provider can read " + genericType + " as " + mediaType, e);
        }
    }

    private void claimContent() {
        if (contentClaimed) {
            throw new IllegalStateException("The entity part content has already been accessed");
        }
        contentClaimed = true;
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
        return headers;
    }

    @Override
    public MediaType getMediaType() {
        return MediaType.valueOf(headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    static final class Builder implements EntityPart.Builder {

        private final String name;
        private final EntityProviders entityProviders;
        private final MultivaluedMap<String, String> headers = new LowercasedMultivaluedHashMap<>();
        private String fileName;
        private InputStream streamContent;
        private Object objectContent;
        private Class<?> rawContentType;
        private Type genericContentType;

        Builder(String name, EntityProviders entityProviders) {
            if (name == null) {
                throw new IllegalArgumentException("The entity part name must not be null");
            }
            this.name = name;
            this.entityProviders = entityProviders;
        }

        @Override
        public EntityPart.Builder mediaType(MediaType mediaType) {
            if (mediaType == null) {
                throw new IllegalArgumentException("The media type must not be null");
            }
            headers.putSingle(HttpHeaders.CONTENT_TYPE, mediaType.toString());
            return this;
        }

        @Override
        public EntityPart.Builder mediaType(String mediaTypeString) {
            if (mediaTypeString == null) {
                throw new IllegalArgumentException("The media type must not be null");
            }
            return mediaType(MediaType.valueOf(mediaTypeString));
        }

        @Override
        public EntityPart.Builder header(String headerName, String... headerValues) {
            if (headerName == null) {
                throw new IllegalArgumentException("The header name must not be null");
            }
            headers.remove(headerName);
            if (headerValues != null) {
                headers.addAll(headerName, headerValues);
            }
            return this;
        }

        @Override
        public EntityPart.Builder headers(MultivaluedMap<String, String> newHeaders) {
            if (newHeaders == null) {
                throw new IllegalArgumentException("The headers must not be null");
            }
            newHeaders.forEach((name, values) -> header(name, values.toArray(new String[0])));
            return this;
        }

        @Override
        public EntityPart.Builder fileName(String fileName) {
            if (fileName == null) {
                throw new IllegalArgumentException("The file name must not be null");
            }
            this.fileName = fileName;
            return this;
        }

        @Override
        public EntityPart.Builder content(InputStream content) {
            if (content == null) {
                throw new IllegalArgumentException("The content must not be null");
            }
            this.streamContent = content;
            this.objectContent = null;
            this.rawContentType = InputStream.class;
            this.genericContentType = InputStream.class;
            return this;
        }

        @Override
        public <T> EntityPart.Builder content(T content, Class<? extends T> type) {
            if (content == null || type == null) {
                throw new IllegalArgumentException("The content and type must not be null");
            }
            this.objectContent = content;
            this.streamContent = null;
            this.rawContentType = type;
            this.genericContentType = type;
            return this;
        }

        @Override
        public EntityPart.Builder content(Object content) {
            if (content == null) {
                throw new IllegalArgumentException("The content must not be null");
            }
            return content(content, content.getClass());
        }

        @Override
        public <T> EntityPart.Builder content(T content, GenericType<T> type) {
            if (content == null || type == null) {
                throw new IllegalArgumentException("The content and type must not be null");
            }
            this.objectContent = content;
            this.streamContent = null;
            this.rawContentType = type.getRawType();
            this.genericContentType = type.getType();
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public EntityPart build() throws IOException, WebApplicationException {
            if (streamContent == null && objectContent == null) {
                throw new IllegalStateException("Entity part content must be specified");
            }
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                mediaType(fileName == null ? MediaType.TEXT_PLAIN_TYPE : MediaType.APPLICATION_OCTET_STREAM_TYPE);
            }
            headers.putSingle(HttpHeaders.CONTENT_DISPOSITION, MultipartEntityProvider.contentDisposition(name, fileName));

            InputStream content = streamContent;
            if (content == null) {
                MediaType mediaType = MediaType.valueOf(headers.getFirst(HttpHeaders.CONTENT_TYPE));
                MessageBodyWriter<Object> writer;
                try {
                    writer = (MessageBodyWriter<Object>) entityProviders.selectWriter(
                        rawContentType, genericContentType, NO_ANNOTATIONS, mediaType);
                } catch (WebApplicationException e) {
                    throw new IllegalStateException("No entity provider can write " + genericContentType + " as " + mediaType, e);
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                MultivaluedHashMap<String, Object> objectHeaders = new MultivaluedHashMap<>();
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    objectHeaders.addAll(entry.getKey(), entry.getValue().toArray());
                }
                writer.writeTo(objectContent, rawContentType, genericContentType, NO_ANNOTATIONS,
                    mediaType, objectHeaders, output);
                content = new ByteArrayInputStream(output.toByteArray());
            }
            return new MuEntityPart(name, fileName, headers, content, entityProviders);
        }
    }
}
