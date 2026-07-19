package io.muserver.rest;

import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

class EntityProviders {

    private final List<ProviderWrapper<MessageBodyReader<?>>> readers;
    final List<ProviderWrapper<MessageBodyWriter<?>>> writers;

    public EntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this.readers = readers.stream().map(ProviderWrapper::reader).sorted().collect(Collectors.toList());
        this.writers = writers.stream().map(ProviderWrapper::writer).sorted().collect(Collectors.toList());
    }
    public MessageBodyReader<?> selectReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType requestBodyMediaType) {
        for (ProviderWrapper<MessageBodyReader<?>> reader : readers) {
            boolean mediaTypeSupported = reader.mediaTypes.stream().anyMatch(mt -> mt.isCompatible(requestBodyMediaType));
            boolean typeSupported = !(reader.genericType instanceof Class) || ((Class<?>) reader.genericType).isAssignableFrom(box(type));
            if (mediaTypeSupported && typeSupported && reader.provider.isReadable(type, genericType, annotations, requestBodyMediaType)) {
                return reader.provider;
            }
        }
        throw new NotSupportedException("Could not find a suitable entity provider to read " + type);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
    public MessageBodyWriter<?> selectWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType responseMediaType) {
        // From 4.2.2

        // 3. SelectthesetofMessageBodyWriterprovidersthatsupport(seeSection4.2.3)theobjectandmedia type of the message entity body.
        Optional<ProviderWrapper<MessageBodyWriter<?>>> best = writers.stream().filter(w -> w.supports(responseMediaType))
            .sorted((o1, o2) -> {
                // 4. Sort the selected MessageBodyWriter providers with a primary key of generic type where providers whose generic
                // type is the nearest superclass of the object class are sorted first

                int typeCompare = ProviderWrapper.compareTo(o1, o2, type);
                if (typeCompare != 0) {
                    return typeCompare;
                }

                // and a secondary key of media type
                Integer min1 = o1.mediaTypes.stream().map(mt -> mt.isWildcardType() ? 2 : mt.isWildcardSubtype() ? 1 : 0).min(Comparator.naturalOrder()).orElse(2);
                Integer min2 = o2.mediaTypes.stream().map(mt -> mt.isWildcardType() ? 2 : mt.isWildcardSubtype() ? 1 : 0).min(Comparator.naturalOrder()).orElse(2);
                int mtCompare = min1.compareTo(min2);
                if (mtCompare != 0) {
                    return mtCompare;
                }

                // Natural order is to prefer user-supplied
                return o1.compareTo(o2);
            })
            .filter(w -> w.provider.isWriteable(type, genericType, annotations, responseMediaType))
            .findFirst();

        if (best.isPresent()) {
            return best.get().provider;
        }
        throw new InternalServerErrorException("Could not find a suitable entity provider to write " + type);
    }

    boolean isBuiltInWriter(MessageBodyWriter<?> writer) {
        return writers.stream().anyMatch(candidate -> candidate.provider == writer && candidate.isBuiltIn);
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

    static Charset charsetFor(MediaType mediaType) {
        String charset = mediaType.getParameters().get("charset");
        if (charset == null) {
            return StandardCharsets.UTF_8;
        } else {
            return Charset.forName(charset);
        }
    }


}
