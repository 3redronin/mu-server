package io.muserver.rest;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

class ProviderWrapper<T> implements Comparable<ProviderWrapper<T>> {

    public final T provider;
    public final boolean isBuiltIn;
    public final List<MediaType> mediaTypes;
    public final Type genericType;

    private ProviderWrapper(T provider, List<MediaType> mediaTypes, Type genericType) {
        this.provider = provider;
        this.isBuiltIn = provider.getClass().getPackage().getName().equals(ProviderWrapper.class.getPackage().getName());
        this.mediaTypes = mediaTypes;
        this.genericType = genericType;
    }

    public static ProviderWrapper<MessageBodyReader<?>> reader(MessageBodyReader<?> provider) {
        List<MediaType> mediaTypes = MediaTypeDeterminer.supportedConsumesTypes(provider.getClass());
        return new ProviderWrapper<>(provider, mediaTypes, genericTypeOf(provider, MessageBodyReader.class));
    }
    public static ProviderWrapper<MessageBodyWriter<?>> writer(MessageBodyWriter<?> provider) {
        List<MediaType> mediaTypes = MediaTypeDeterminer.supportedProducesTypes(provider.getClass());
        return new ProviderWrapper<>(provider, mediaTypes, genericTypeOf(provider, MessageBodyWriter.class));
    }

    public static Type genericTypeOf(Object instance, Class implementedInterface) {
        if (instance instanceof PrimitiveEntityProvider) {
            return ((PrimitiveEntityProvider) instance).boxedClass;
        }
        for (Type type : instance.getClass().getGenericInterfaces()) {
            if (type instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) type;
                if (pt.getRawType().equals(implementedInterface)) {
                    return pt.getActualTypeArguments()[0];
                }
            }
        }
        return Object.class;
    }

    public boolean supports(MediaType mediaType) {
        return mediaTypes.isEmpty() || mediaTypes.stream().anyMatch(mt -> mt.isCompatible(mediaType));
    }

    @Override
    public int compareTo(ProviderWrapper<T> o) {
        return Boolean.compare(this.isBuiltIn, o.isBuiltIn);
    }

    @SuppressWarnings("unchecked")
    public static int compareTo(ProviderWrapper<MessageBodyWriter<?>> o1, ProviderWrapper<MessageBodyWriter<?>> o2, Type genericType) {
        if (o1.genericType.equals(o2.genericType)) {
            return 0;
        }
        if (!(o1.genericType instanceof Class && o2.genericType instanceof Class && genericType instanceof Class)) {
            return 0;
        }
        Class o1C = (Class) o1.genericType;
        Class o2C = (Class) o2.genericType;
        Class oC = (Class) genericType;
        boolean o1Is = oC.isAssignableFrom(o1C);
        boolean o2Is = oC.isAssignableFrom(o2C);
        int assignCompare = Boolean.compare(o2Is, o1Is);
        if (assignCompare != 0) {
            return assignCompare;
        }
        return o1C.isAssignableFrom(o2C) ? -1 : 1;
    }

    @Override
    public String toString() {
        return "ProviderWrapper{" +
            "provider=" + provider +
            ", isBuiltIn=" + isBuiltIn +
            ", mediaTypes=" + mediaTypes +
            ", genericType=" + genericType +
            '}';
    }
}
