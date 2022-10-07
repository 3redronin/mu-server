package io.muserver.rest;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotSupportedException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

class LegacyEntityProviders {

    private final List<LegacyProviderWrapper<MessageBodyReader<?>>> readers;
    final List<LegacyProviderWrapper<MessageBodyWriter<?>>> writers;

    public LegacyEntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this.readers = readers.stream().map(LegacyProviderWrapper::reader).sorted().collect(Collectors.toList());
        this.writers = writers.stream().map(LegacyProviderWrapper::writer).sorted().collect(Collectors.toList());
    }
    public MessageBodyReader<?> selectReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType requestBodyMediaType) {
        for (LegacyProviderWrapper<MessageBodyReader<?>> reader : readers) {
            boolean mediaTypeSupported = reader.mediaTypes.stream().anyMatch(mt -> mt.isCompatible(requestBodyMediaType));
            if (mediaTypeSupported && reader.provider.isReadable(type, genericType, annotations, requestBodyMediaType)) {
                return reader.provider;
            }
        }
        throw new NotSupportedException("Could not find a suitable entity provider to read " + type);
    }
    public MessageBodyWriter<?> selectWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType responseMediaType) {
        // From 4.2.2

        // 3. SelectthesetofMessageBodyWriterprovidersthatsupport(seeSection4.2.3)theobjectandmedia type of the message entity body.
        Optional<LegacyProviderWrapper<MessageBodyWriter<?>>> best = writers.stream().filter(w -> w.supports(responseMediaType))
            .sorted((o1, o2) -> {
                // 4. Sort the selected MessageBodyWriter providers with a primary key of generic type where providers whose generic
                // type is the nearest superclass of the object class are sorted first

                int typeCompare = LegacyProviderWrapper.compareTo(o1, o2, genericType);
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

    public static List<MessageBodyReader> builtInReaders() {
        List<MessageBodyReader> readers = new ArrayList<>();
        readers.addAll(LegacyStringEntityProviders.stringEntityReaders);
        readers.addAll(LegacyPrimitiveEntityProvider.primitiveEntryProviders);
        readers.addAll(LegacyBinaryEntityProviders.binaryEntityReaders);
        return readers;
    }
    public static List<MessageBodyWriter> builtInWriters() {
        List<MessageBodyWriter> writers = new ArrayList<>();
        writers.addAll(LegacyStringEntityProviders.stringEntityWriters);
        writers.addAll(LegacyPrimitiveEntityProvider.primitiveEntryProviders);
        writers.addAll(LegacyBinaryEntityProviders.binaryEntityWriters);
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

