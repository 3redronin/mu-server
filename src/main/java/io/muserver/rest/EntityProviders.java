package io.muserver.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class EntityProviders {

    private final List<ProviderWrapper<MessageBodyReader<?>>> readers;
    final List<ProviderWrapper<MessageBodyWriter<?>>> writers;

    public EntityProviders(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        this(new ArrayList<>(), readers, new ArrayList<>(), writers);
    }

    public EntityProviders(List<MessageBodyReader> builtInReaders, List<MessageBodyReader> customReaders,
                           List<MessageBodyWriter> builtInWriters, List<MessageBodyWriter> customWriters) {
        this.readers = new ArrayList<>();
        builtInReaders.stream().map(reader -> ProviderWrapper.reader(reader, true)).forEach(this.readers::add);
        customReaders.stream().map(reader -> ProviderWrapper.reader(reader, false)).forEach(this.readers::add);
        this.readers.sort(ProviderWrapper::compareTo);

        this.writers = new ArrayList<>();
        builtInWriters.stream().map(writer -> ProviderWrapper.writer(writer, true)).forEach(this.writers::add);
        customWriters.stream().map(writer -> ProviderWrapper.writer(writer, false)).forEach(this.writers::add);
        this.writers.sort(ProviderWrapper::compareTo);
    }

    public @Nullable MessageBodyReader<?> findReader(Class<?> type, Type genericType, Annotation[] annotations, MediaType requestBodyMediaType) {
        return readers.stream()
            .filter(reader -> reader.supports(requestBodyMediaType))
            .filter(reader -> !(reader.genericType instanceof Class) || ((Class<?>) reader.genericType).isAssignableFrom(box(type)))
            .sorted((first, second) -> {
                int mediaTypeCompare = Integer.compare(
                    mediaTypeSpecificity(first, requestBodyMediaType),
                    mediaTypeSpecificity(second, requestBodyMediaType));
                if (mediaTypeCompare != 0) {
                    return mediaTypeCompare;
                }
                return first.compareTo(second);
            })
            .filter(reader -> reader.provider.isReadable(type, genericType, annotations, requestBodyMediaType))
            .map(reader -> reader.provider)
            .findFirst()
            .orElse(null);
    }

    private static int mediaTypeSpecificity(ProviderWrapper<?> provider, MediaType requestedType) {
        return provider.mediaTypes.stream()
            .filter(mediaType -> MediaTypeHeaderDelegate.isCompatible(mediaType, requestedType))
            .mapToInt(EntityProviders::mediaTypeSpecificity)
            .min()
            .orElse(2);
    }

    private static int mediaTypeSpecificity(MediaType mediaType) {
        return mediaType.isWildcardType()
            ? 2
            : MediaTypeHeaderDelegate.isWildcardSubtype(mediaType.getSubtype()) ? 1 : 0;
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
    public @Nullable MessageBodyWriter<?> findWriter(Class<?> type, Type genericType, Annotation[] annotations, MediaType responseMediaType) {
        // From 4.2.2

        // 3. SelectthesetofMessageBodyWriterprovidersthatsupport(seeSection4.2.3)theobjectandmedia type of the message entity body.
        return writers.stream().filter(w -> w.supports(responseMediaType))
            .sorted((o1, o2) -> {
                // Application-provided providers take precedence over built-in providers.
                int providerCompare = o1.compareTo(o2);
                if (providerCompare != 0) {
                    return providerCompare;
                }

                // 4. Sort the selected MessageBodyWriter providers with a primary key of generic type where providers whose generic
                // type is the nearest superclass of the object class are sorted first

                int typeCompare = ProviderWrapper.compareTo(o1, o2, type);
                if (typeCompare != 0) {
                    return typeCompare;
                }

                // and a secondary key of media type
                Integer min1 = o1.mediaTypes.stream().map(EntityProviders::mediaTypeSpecificity).min(Comparator.naturalOrder()).orElse(2);
                Integer min2 = o2.mediaTypes.stream().map(EntityProviders::mediaTypeSpecificity).min(Comparator.naturalOrder()).orElse(2);
                int mtCompare = min1.compareTo(min2);
                if (mtCompare != 0) {
                    return mtCompare;
                }

                return 0;
            })
            .filter(w -> w.provider.isWriteable(type, genericType, annotations, responseMediaType))
            .map(writer -> writer.provider)
            .findFirst()
            .orElse(null);
    }

    boolean isBuiltInWriter(MessageBodyWriter<?> writer) {
        return writers.stream().anyMatch(candidate -> {
            // Provider selection returns the exact instance held by its wrapper.
            @SuppressWarnings("ReferenceEquality")
            boolean sameProvider = candidate.provider == writer;
            return sameProvider && candidate.isBuiltIn;
        });
    }

    public static List<MessageBodyReader> builtInReaders() {
        List<MessageBodyReader> readers = new ArrayList<>();
        readers.addAll(StringEntityProviders.stringEntityReaders);
        readers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        readers.addAll(BinaryEntityProviders.binaryEntityReaders);
        readers.addAll(SourceEntityProviders.sourceEntityReaders);
        return readers;
    }
    public static List<MessageBodyWriter> builtInWriters() {
        List<MessageBodyWriter> writers = new ArrayList<>();
        writers.addAll(StringEntityProviders.stringEntityWriters);
        writers.addAll(PrimitiveEntityProvider.primitiveEntryProviders);
        writers.addAll(BinaryEntityProviders.binaryEntityWriters);
        writers.addAll(SourceEntityProviders.sourceEntityWriters);
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
