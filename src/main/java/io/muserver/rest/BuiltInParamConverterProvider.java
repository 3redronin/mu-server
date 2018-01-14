package io.muserver.rest;

import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;

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
        if (rawType.isEnum()) {
            return new EnumConverter(rawType);
        }
        ConstructorConverter<T> cc = ConstructorConverter.tryToCreate(rawType);
        if (cc != null) {
            return cc;
        }
        StaticMethodConverter<T> smc = StaticMethodConverter.tryToCreate(rawType);
        if (smc != null) {
            return smc;
        }
        return null;
    }


    private static class BoxedPrimitiveConverter<T> implements ParamConverter<T> {
        private final Class<T> boxedClass;
        private final Function<String, T> stringToValue;
        public BoxedPrimitiveConverter(Class<T> boxedClass, Function<String, T> stringToValue) {
            this.boxedClass = boxedClass;
            this.stringToValue = stringToValue;
        }
        public T fromString(String value) {
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
            return Enum.valueOf(enumClass, value);
        }
        public String toString(E value) {
            return value.name();
        }
        public String toString() {
            return enumClass.getSimpleName() + " converter";
        }
    }

    private static class ConstructorConverter<T> implements ParamConverter<T> {
        private final Constructor<T> constructor;
        private ConstructorConverter(Constructor<T> constructor) {
            this.constructor = constructor;
        }
        public T fromString(String value) {
            try {
                return constructor.newInstance(value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not convert \"" + value + "\" to a " + constructor.getDeclaringClass().getSimpleName());
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
            try {
                return (T) staticMethod.invoke(null, value);
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not convert \"" + value + "\" to a " + staticMethod.getDeclaringClass().getSimpleName());
            }
        }
        public String toString(T value) {
            return String.valueOf(value);
        }
        public String toString() {
            return "StaticMethodConverter{" + staticMethod + '}';
        }

        static <T> StaticMethodConverter<T> tryToCreate(Class clazz) {
            Method staticMethod = null;
            for (Method method : clazz.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && method.getReturnType().equals(clazz)) {
                    if (method.getName().equals("valueOf")) {
                        staticMethod = method;
                        break;
                    }
                    if (method.getName().equals("fromString")) {
                        staticMethod = method;
                        break;
                    }
                }
            }
            if (staticMethod == null) {
                return null;
            }
            staticMethod.setAccessible(true);
            return new StaticMethodConverter<>(staticMethod);
        }
    }

}
