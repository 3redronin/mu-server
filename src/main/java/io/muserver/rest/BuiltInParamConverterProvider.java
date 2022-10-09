package io.muserver.rest;

import io.muserver.Cookie;
import io.muserver.Mutils;
import io.muserver.UploadedFile;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;

class BuiltInParamConverterProvider implements ParamConverterProvider {

    private final ParamConverter<String> stringParamConverter = new ParamConverter<String>() {
        public String fromString(String value) {
            return value;
        }
        public String toString(String value) {
            return value;
        }
    };

    private static final List<PrimitiveConverter> primitiveConverters = asList(
        new PrimitiveConverter<>(int.class, 0, Integer::parseInt),
        new PrimitiveConverter<>(long.class, 0L, Long::parseLong),
        new PrimitiveConverter<>(short.class, (short)0, Short::parseShort),
        new PrimitiveConverter<>(char.class, '\u0000', s -> s.charAt(0)),
        new PrimitiveConverter<>(byte.class, (byte)0, Byte::parseByte),
        new PrimitiveConverter<>(float.class, 0.0f, Float::parseFloat),
        new PrimitiveConverter<>(double.class, 0.0d, Double::parseDouble),
        new PrimitiveConverter<>(boolean.class, false, Boolean::parseBoolean)
    );
    private static final List<BoxedPrimitiveConverter> boxedPrimitiveConverters = asList(
        new BoxedPrimitiveConverter<>(Integer.class, Integer::parseInt),
        new BoxedPrimitiveConverter<>(Long.class, Long::parseLong),
        new BoxedPrimitiveConverter<>(Short.class, Short::parseShort),
        new BoxedPrimitiveConverter<>(Character.class, s -> s.charAt(0)),
        new BoxedPrimitiveConverter<>(Byte.class, Byte::parseByte),
        new BoxedPrimitiveConverter<>(Float.class, Float::parseFloat),
        new BoxedPrimitiveConverter<>(Double.class, Double::parseDouble),
        new BoxedPrimitiveConverter<>(Boolean.class, Boolean::parseBoolean)
    );


    @Override
    public <T> ParamConverter getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {

        if (String.class.isAssignableFrom(rawType)) {
            return stringParamConverter;
        }
        for (PrimitiveConverter converter : primitiveConverters) {
            if (converter.primitiveClass.equals(rawType)) {
                return converter;
            }
        }
        for (BoxedPrimitiveConverter converter : boxedPrimitiveConverters) {
            if (converter.boxedClass.isAssignableFrom(rawType)) {
                return converter;
            }
        }
        if (UploadedFile.class.isAssignableFrom(rawType)) {
            return new UploadedFileConverter();
        }
        if (PathSegment.class.isAssignableFrom(rawType)) {
            return new PathSegmentConverter();
        }
        if (javax.ws.rs.core.PathSegment.class.isAssignableFrom(rawType)) {
            return new LegacyPathSegmentConverter();
        }
        if (Cookie.class.isAssignableFrom(rawType)) {
            return new DummyCookieConverter();
        }
        if (javax.ws.rs.core.Cookie.class.isAssignableFrom(rawType)) {
            return new LegacyDummyCookieConverter();
        }
        if (rawType.isEnum()) {
            return new EnumConverter(rawType);
        }

        ConstructorConverter<T> cc = ConstructorConverter.tryToCreate(rawType);
        if (cc != null) {
            return cc;
        }
        StaticMethodConverter<T> smc = StaticMethodConverter.tryToCreate(rawType);
        // may be null
        return smc;
    }

    private static class UploadedFileConverter implements ParamConverter<UploadedFile> {
        @Override
        public UploadedFile fromString(String value) {
            return null;
        }
        @Override
        public String toString(UploadedFile value) {
            return value.filename();
        }
    }

    // Not actually used because it is handled specially in ResourceMethodParam but needs to exist during parameter setup
    private static class DummyCookieConverter implements ParamConverter<Cookie> {
        @Override
        public Cookie fromString(String value) {
            return null;
        }
        @Override
        public String toString(Cookie value) {
            return value.toString();
        }
    }
    private static class LegacyDummyCookieConverter implements ParamConverter<javax.ws.rs.core.Cookie> {
        @Override
        public javax.ws.rs.core.Cookie fromString(String value) {
            return null;
        }
        @Override
        public String toString(javax.ws.rs.core.Cookie value) {
            return value.toString();
        }
    }

    private static class PathSegmentConverter implements ParamConverter<PathSegment> {
        @Override
        public PathSegment fromString(String value) {
            if (value == null) throw new IllegalArgumentException("value cannot be null");
            MuPathSegment seg = MuUriInfo.pathStringToSegments(value, true).findFirst().orElse(null);
            if (seg == null) throw new IllegalArgumentException("Could not parse a path segment");
            return seg;
        }
        @Override
        public String toString(PathSegment value) {
            if (value == null) throw new IllegalArgumentException("value cannot be null");
            return value.toString();
        }
    }

    private static class LegacyPathSegmentConverter implements ParamConverter<javax.ws.rs.core.PathSegment> {
        @Override
        public javax.ws.rs.core.PathSegment fromString(String value) {
            if (value == null) throw new IllegalArgumentException("value cannot be null");
            LegacyMuPathSegment seg = LegacyMuUriInfo.pathStringToSegments(value, true).findFirst().orElse(null);
            if (seg == null) throw new IllegalArgumentException("Could not parse a path segment");
            return seg;
        }
        @Override
        public String toString(javax.ws.rs.core.PathSegment value) {
            if (value == null) throw new IllegalArgumentException("value cannot be null");
            return value.toString();
        }
    }

    private static class BoxedPrimitiveConverter<T> implements ParamConverter<T> {
        private final Class<T> boxedClass;
        private final Function<String, T> stringToValue;
        public BoxedPrimitiveConverter(Class<T> boxedClass, Function<String, T> stringToValue) {
            this.boxedClass = boxedClass;
            this.stringToValue = stringToValue;
        }
        public T fromString(String value) {
            if (Mutils.nullOrEmpty(value)) return null;
            return stringToValue.apply(value);
        }
        public String toString(T value) {
            return String.valueOf(value);
        }
        public String toString() {
            return boxedClass.getSimpleName() + " param converter";
        }
    }

    private static class PrimitiveConverter<T> implements ParamConverter<T>, ResourceMethodParam.HasDefaultValue {
        private final T defaultValue;
        private final Class primitiveClass;
        private final Function<String, T> stringToValue;

        public PrimitiveConverter(Class primitiveClass, T defaultValue, Function<String, T> stringToValue) {
            this.defaultValue = defaultValue;
            this.primitiveClass = primitiveClass;
            this.stringToValue = stringToValue;
        }
        public T fromString(String value) {
            if (value == null || value.isEmpty()) {
                return defaultValue;
            }
            return stringToValue.apply(value);
        }
        public String toString(T value) {
            return String.valueOf(value);
        }
        public Object getDefault() {
            return defaultValue;
        }
        public String toString() {
            return primitiveClass.getSimpleName() + " param converter";
        }
    }

    private static class EnumConverter<E extends Enum<E>>  implements ParamConverter<E> {
        private final Class<E> enumClass;
        private EnumConverter(Class<E> enumClass) {
            this.enumClass = enumClass;
        }
        public E fromString(String value) {
            if (Mutils.nullOrEmpty(value)) return null;
            return Enum.valueOf(enumClass, value);
        }
        public String toString(E value) {
            return value.name();
        }
        public String toString() {
            String validValues = Stream.of(enumClass.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
            return enumClass.getSimpleName() + " converter (valid values: " + validValues + ")";
        }
    }

    private static class ConstructorConverter<T> implements ParamConverter<T> {
        private final Constructor<T> constructor;
        private ConstructorConverter(Constructor<T> constructor) {
            this.constructor = constructor;
        }
        public T fromString(String value) {
            if (Mutils.nullOrEmpty(value)) return null;
            try {
                return constructor.newInstance(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not convert \"" + value + "\" to a " + constructor.getDeclaringClass().getSimpleName(), e);
            }
        }
        public String toString(T value) {
            return String.valueOf(value);
        }
        public String toString() {
            return "ConstructorConverter{" + constructor + '}';
        }
        static <T> ConstructorConverter<T> tryToCreate(Class<T> clazz) {
            try {
                Constructor constructor = clazz.getConstructor(String.class);
                int modifiers = constructor.getModifiers();
                if (Modifier.isPublic(modifiers)) {
                    constructor.setAccessible(true);
                    return new ConstructorConverter<>(constructor);
                }
                return null;
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }

    private static class StaticMethodConverter<T> implements ParamConverter<T> {
        private final Method staticMethod;
        private StaticMethodConverter(Method staticMethod) {
            this.staticMethod = staticMethod;
        }
        public T fromString(String value) {
            if (Mutils.nullOrEmpty(value)) return null;
            try {
                return (T) staticMethod.invoke(null, value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not convert \"" + value + "\" to a " + staticMethod.getDeclaringClass().getSimpleName() + " because " + e.getMessage(), e);
            }
        }
        public String toString(T value) {
            return String.valueOf(value);
        }
        public String toString() {
            return staticMethod.toString();
        }

        static <T> StaticMethodConverter<T> tryToCreate(Class clazz) {
            Method[] declaredMethods = clazz.getDeclaredMethods();
            Method staticMethod = getSingleParamPublicStaticMethodNamed(clazz, declaredMethods, "valueOf");
            if (staticMethod == null) {
                staticMethod = getSingleParamPublicStaticMethodNamed(clazz, declaredMethods, "fromString");
            }
            if (staticMethod == null) {
                staticMethod = getSingleParamPublicStaticMethodNamed(clazz, declaredMethods, "parse");
            }
            if (staticMethod == null) {
                return null;
            }
            staticMethod.setAccessible(true);
            return new StaticMethodConverter<>(staticMethod);
        }

        private static Method getSingleParamPublicStaticMethodNamed(Class clazz, Method[] declaredMethods, String name) {
            Method staticMethod = null;
            for (Method method : declaredMethods) {
                int modifiers = method.getModifiers();
                if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && method.getReturnType().equals(clazz)) {
                    if (method.getName().equals(name) && method.getParameterCount() == 1 && CharSequence.class.isAssignableFrom(method.getParameterTypes()[0])) {
                        staticMethod = method;
                        break;
                    }
                }
            }
            return staticMethod;
        }
    }

}
