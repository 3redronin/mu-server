package io.muserver.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

class ProviderWrapper<T> implements Comparable<ProviderWrapper<T>> {

    public final T provider;
    public final boolean isBuiltIn;
    public final List<MediaType> mediaTypes;
    public final Type genericType;

    private ProviderWrapper(T provider, List<MediaType> mediaTypes, Type genericType, boolean isBuiltIn) {
        this.provider = provider;
        this.isBuiltIn = isBuiltIn;
        this.mediaTypes = mediaTypes;
        this.genericType = genericType;
    }

    public static ProviderWrapper<MessageBodyReader<?>> reader(MessageBodyReader<?> provider) {
        return reader(provider, false);
    }

    public static ProviderWrapper<MessageBodyReader<?>> reader(MessageBodyReader<?> provider, boolean isBuiltIn) {
        List<MediaType> mediaTypes = MediaTypeDeterminer.supportedConsumesTypes(provider.getClass());
        return new ProviderWrapper<>(provider, mediaTypes, genericTypeOf(provider, MessageBodyReader.class), isBuiltIn);
    }

    public static ProviderWrapper<MessageBodyWriter<?>> writer(MessageBodyWriter<?> provider) {
        return writer(provider, false);
    }

    public static ProviderWrapper<MessageBodyWriter<?>> writer(MessageBodyWriter<?> provider, boolean isBuiltIn) {
        List<MediaType> mediaTypes = MediaTypeDeterminer.supportedProducesTypes(provider.getClass());
        return new ProviderWrapper<>(provider, mediaTypes, genericTypeOf(provider, MessageBodyWriter.class), isBuiltIn);
    }

    public static Type genericTypeOf(Object instance, Class implementedInterface) {
        if (instance instanceof PrimitiveEntityProvider) {
            return ((PrimitiveEntityProvider) instance).boxedClass;
        }
        return genericTypeOf(instance.getClass(), implementedInterface);
    }

    private static Type genericTypeOf(Class<?> type, Class implementedInterface) {
        for (Type interfaceType : type.getGenericInterfaces()) {
            if (interfaceType instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) interfaceType;
                if (pt.getRawType().equals(implementedInterface)) {
                    return pt.getActualTypeArguments()[0];
                }
                if (pt.getRawType() instanceof Class) {
                    Type nested = genericTypeOf((Class<?>) pt.getRawType(), implementedInterface);
                    if (nested != Object.class) {
                        return nested;
                    }
                }
            } else if (interfaceType instanceof Class) {
                Type nested = genericTypeOf((Class<?>) interfaceType, implementedInterface);
                if (nested != Object.class) {
                    return nested;
                }
            }
        }
        Type superclass = type.getGenericSuperclass();
        if (superclass instanceof Class) {
            return genericTypeOf((Class<?>) superclass, implementedInterface);
        }
        if (superclass instanceof ParameterizedType && ((ParameterizedType) superclass).getRawType() instanceof Class) {
            return genericTypeOf((Class<?>) ((ParameterizedType) superclass).getRawType(), implementedInterface);
        }
        return Object.class;
    }

    public boolean supports(MediaType mediaType) {
        return mediaTypes.isEmpty() || mediaTypes.stream().anyMatch(mt -> MediaTypeHeaderDelegate.isCompatible(mt, mediaType));
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
        boolean o1Is = o1C.isAssignableFrom(oC);
        boolean o2Is = o2C.isAssignableFrom(oC);
        int assignCompare = Boolean.compare(o2Is, o1Is);
        if (assignCompare != 0) {
            return assignCompare;
        }
        if (o1C.isAssignableFrom(o2C)) {
            return o1Is ? 1 : -1;
        }
        if (o2C.isAssignableFrom(o1C)) {
            return o1Is ? -1 : 1;
        }
        return 0;
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
