package io.muserver.rest;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotSupportedException;
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
import java.util.Optional;

class EntityProviders {

    private final List<MessageBodyReader> readers;
    final List<MessageBodyWriter> writers;

    public EntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this.readers = readers;
        this.writers = writers;
    }
    public MessageBodyReader<?> selectReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType requestBodyMediaType) {
        for (MessageBodyReader<?> reader : readers) {
            List<MediaType> mediaTypes = MediaTypeDeterminer.supportedConsumesTypes(reader.getClass());
            boolean mediaTypeSupported = mediaTypes.stream().anyMatch(mt -> mt.isCompatible(requestBodyMediaType));
            if (mediaTypeSupported && reader.isReadable(type, genericType, annotations, requestBodyMediaType)) {
                return reader;
            }
        }
        throw new NotSupportedException("Could not find a suitable entity provider to read " + type);
    }
    public MessageBodyWriter<?> selectWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType responseMediaType) {
        // From 4.2.2


        // 3. SelectthesetofMessageBodyWriterprovidersthatsupport(seeSection4.2.3)theobjectandmedia type of the message entity body.
        Optional<MessageBodyWriter> best = writers.stream().filter(w -> {
            List<MediaType> writerProduces = MediaTypeDeterminer.supportedProducesTypes(w.getClass());
            return writerProduces.stream().anyMatch(mt -> mt.isCompatible(responseMediaType));
        })
            .sorted((o1, o2) -> {
                // 4. Sort the selected MessageBodyWriter providers with a primary key of generic type where providers whose generic
                // type is the nearest superclass of the object class are sorted first and a secondary key of media type
                // TODO implement this bit by creating a new class that wraps MethodBodyWriter and provides access to consumes lists
                return 0;
            })
            .filter(w -> w.isWriteable(type, genericType, annotations, responseMediaType))
            .findFirst();

        if (best.isPresent()) {
            return best.get();
        }
        throw new InternalServerErrorException("Could not find a suitable entity provider to write " + type);
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

