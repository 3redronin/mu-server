package io.muserver.rest;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
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

        if (Collection.class.isAssignableFrom(rawType) && genericType instanceof ParameterizedType) {
            Type type = ((ParameterizedType) genericType).getActualTypeArguments()[0];
            if (type instanceof Class) {
                Class genericClass = (Class) type;
                ParamConverter genericTypeConverter = getConverter(genericClass, type, annotations);
                if (genericTypeConverter != null) {
                    CollectionConverter collectionConverter = CollectionConverter.create(rawType, genericClass, genericTypeConverter);
                    if (collectionConverter != null) {
                        return collectionConverter;
                    }
                }
            }
        }
        return null;
    }

    private static class CollectionConverter implements ParamConverter {
        private final ParamConverter genericTypeConverter;
        private final Supplier collectionSupplier;
        private CollectionConverter(ParamConverter genericTypeConverter, Supplier collectionSupplier) {
            this.genericTypeConverter = genericTypeConverter;
            this.collectionSupplier = collectionSupplier;
        }
        public Object fromString(String value) {
            Collection values = (Collection)collectionSupplier.get();
            String[] parts = value.split(",");
            Stream.of(parts).map(v -> genericTypeConverter.fromString(v))
                .forEach(values::add);
            return values;
        }
        public String toString(Object value) {
            Collection<?> collection = (Collection) value;
            return collection.stream().map(genericTypeConverter::toString).collect(Collectors.joining(""));
        }
        public static CollectionConverter create(Class collectionType, Class genericClass, ParamConverter genericTypeConverter) {
            Supplier supplier;
            if (SortedSet.class.equals(collectionType)) {
                if (!Comparable.class.isAssignableFrom(genericClass)) {
                    throw new InternalServerErrorException("The class " + genericClass + " does not implement Comparable so cannot be used in a SortedSet");
                }
                supplier = TreeSet::new;
            } else if (Set.class.equals(collectionType)) {
                supplier = HashSet::new;
            } else if (List.class.equals(collectionType)) {
                supplier = ArrayList::new;
            } else {
                return null;
            }
            return new CollectionConverter(genericTypeConverter, supplier);
        }
        public String toString() {
            return "CollectionConverter<" + genericTypeConverter + '>';
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
            return "StaticMethodConverter{" + staticMethod + '}';
        }

        static <T> StaticMethodConverter<T> tryToCreate(Class clazz) {
            Method[] declaredMethods = clazz.getDeclaredMethods();
            Method staticMethod = getPublicStaticMethodNamed(clazz, declaredMethods, "valueOf");
            if (staticMethod == null) {
                staticMethod = getPublicStaticMethodNamed(clazz, declaredMethods, "fromString");
            }
            if (staticMethod == null) {
                return null;
            }
            staticMethod.setAccessible(true);
            return new StaticMethodConverter<>(staticMethod);
        }

        private static Method getPublicStaticMethodNamed(Class clazz, Method[] declaredMethods, String name) {
            Method staticMethod = null;
            for (Method method : declaredMethods) {
                int modifiers = method.getModifiers();
                if (Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers) && method.getReturnType().equals(clazz)) {
                    if (method.getName().equals(name)) {
                        staticMethod = method;
                        break;
                    }
                }
            }
            return staticMethod;
        }
    }

}
