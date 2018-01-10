package io.muserver.rest;

import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

class EntityProviders {

    final List<MessageBodyReader> readers;
    final List<MessageBodyWriter> writers;

    public EntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this.readers = readers;
        this.writers = writers;
    }
    public MessageBodyWriter<?> selectWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        for (MessageBodyWriter<?> writer : writers) {
            if (writer.isWriteable(type, genericType, annotations, mediaType)) {
                return writer;
            }
        }
        throw new NotAcceptableException("Could not find a suitable entity provider to write " + type);
    }
    public MessageBodyReader<?> selectReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        for (MessageBodyReader<?> reader : readers) {
            if (reader.isReadable(type, genericType, annotations, mediaType)) {
                return reader;
            }
        }
        throw new NotAcceptableException("Could not find a suitable entity provider to read " + type);
    }

    public static List<MessageBodyReader> builtInReaders() {
        List<MessageBodyReader> readers = new ArrayList<>();
        readers.addAll(StringEntityProviders.stringEntityReaders);
        readers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        readers.addAll(BinaryEntityProviders.binaryEntityReaders);
        return readers;
    }
    public static List<MessageBodyWriter> builtInWriters() {
        List<MessageBodyWriter> writers = new ArrayList<>();
        writers.addAll(StringEntityProviders.stringEntityWriters);
        writers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        writers.addAll(BinaryEntityProviders.binaryEntityWriters);
        return writers;
    }


    static boolean requestHasContent(MultivaluedMap<String, String> headers) {
        String len = headers.getFirst("Content-Length");
        return headers.containsKey("Transfer-Encoding") || (len != null && Long.parseLong(len) > 0);
    }

    static Charset charsetFor(MediaType mediaType) {
        String charset = mediaType.getParameters().get("charset");
        if (charset == null) {
            return StandardCharsets.UTF_8;
        } else {
            return Charset.forName(charset);
        }
    }
}

