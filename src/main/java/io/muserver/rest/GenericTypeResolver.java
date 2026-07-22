package io.muserver.rest;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class GenericTypeResolver {

    private GenericTypeResolver() { }

    static Type resolve(Type type, Class<?> concreteClass, Class<?> declaringClass) {
        Map<TypeVariable<?>, Type> typeArguments = new HashMap<>();
        if (!findTypeArguments(concreteClass, declaringClass, new HashMap<>(), typeArguments)) {
            return type;
        }
        return resolve(type, typeArguments);
    }

    static Type resolveTypeArgument(Type type, Class<?> targetClass, int argumentIndex) {
        TypeVariable<?> typeVariable = targetClass.getTypeParameters()[argumentIndex];
        Map<TypeVariable<?>, Type> typeArguments = new HashMap<>();
        if (!findTypeArguments(type, targetClass, new HashMap<>(), typeArguments)) {
            return null;
        }
        Type resolved = resolve(typeVariable, typeArguments);
        return containsTypeVariable(resolved) ? null : resolved;
    }

    private static boolean containsTypeVariable(Type type) {
        if (type instanceof TypeVariable) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            if (parameterizedType.getOwnerType() != null && containsTypeVariable(parameterizedType.getOwnerType())) {
                return true;
            }
            return Arrays.stream(parameterizedType.getActualTypeArguments())
                .anyMatch(GenericTypeResolver::containsTypeVariable);
        }
        if (type instanceof GenericArrayType) {
            return containsTypeVariable(((GenericArrayType) type).getGenericComponentType());
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            return Arrays.stream(wildcardType.getUpperBounds()).anyMatch(GenericTypeResolver::containsTypeVariable)
                || Arrays.stream(wildcardType.getLowerBounds()).anyMatch(GenericTypeResolver::containsTypeVariable);
        }
        return false;
    }

    private static boolean findTypeArguments(Type currentType, Class<?> declaringClass,
                                             Map<TypeVariable<?>, Type> inheritedArguments,
                                             Map<TypeVariable<?>, Type> result) {
        Class<?> currentClass = rawClass(currentType);
        if (currentClass == null) {
            return false;
        }

        Map<TypeVariable<?>, Type> currentArguments = new HashMap<>(inheritedArguments);
        if (currentType instanceof ParameterizedType) {
            TypeVariable<?>[] variables = currentClass.getTypeParameters();
            Type[] arguments = ((ParameterizedType) currentType).getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) {
                currentArguments.put(variables[i], resolve(arguments[i], inheritedArguments));
            }
        }

        if (currentClass.equals(declaringClass)) {
            result.putAll(currentArguments);
            return true;
        }

        Type genericSuperclass = currentClass.getGenericSuperclass();
        if (isOnPath(genericSuperclass, declaringClass)
            && findTypeArguments(genericSuperclass, declaringClass, currentArguments, result)) {
            return true;
        }
        for (Type genericInterface : currentClass.getGenericInterfaces()) {
            if (isOnPath(genericInterface, declaringClass)
                && findTypeArguments(genericInterface, declaringClass, currentArguments, result)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnPath(Type candidate, Class<?> declaringClass) {
        Class<?> candidateClass = rawClass(candidate);
        return candidateClass != null && declaringClass.isAssignableFrom(candidateClass);
    }

    static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() instanceof Class) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return null;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> typeArguments) {
        if (type instanceof TypeVariable) {
            Type resolved = typeArguments.get(type);
            return resolved == null || resolved.equals(type) ? type : resolve(resolved, typeArguments);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type owner = parameterizedType.getOwnerType();
            Type resolvedOwner = owner == null ? null : resolve(owner, typeArguments);
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Type[] resolvedArguments = resolve(arguments, typeArguments);
            if (resolvedOwner == owner && Arrays.equals(arguments, resolvedArguments)) {
                return type;
            }
            return new ResolvedParameterizedType(resolvedOwner, parameterizedType.getRawType(), resolvedArguments);
        }
        if (type instanceof GenericArrayType) {
            GenericArrayType arrayType = (GenericArrayType) type;
            Type component = resolve(arrayType.getGenericComponentType(), typeArguments);
            if (component instanceof Class) {
                return Array.newInstance((Class<?>) component, 0).getClass();
            }
            return component.equals(arrayType.getGenericComponentType()) ? type : new ResolvedGenericArrayType(component);
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] upperBounds = resolve(wildcardType.getUpperBounds(), typeArguments);
            Type[] lowerBounds = resolve(wildcardType.getLowerBounds(), typeArguments);
            if (Arrays.equals(upperBounds, wildcardType.getUpperBounds())
                && Arrays.equals(lowerBounds, wildcardType.getLowerBounds())) {
                return type;
            }
            return new ResolvedWildcardType(upperBounds, lowerBounds);
        }
        return type;
    }

    private static Type[] resolve(Type[] types, Map<TypeVariable<?>, Type> typeArguments) {
        Type[] resolved = types.clone();
        for (int i = 0; i < resolved.length; i++) {
            resolved[i] = resolve(resolved[i], typeArguments);
        }
        return resolved;
    }

    private static final class ResolvedParameterizedType implements ParameterizedType {
        private final Type owner;
        private final Type rawType;
        private final Type[] arguments;

        private ResolvedParameterizedType(Type owner, Type rawType, Type[] arguments) {
            this.owner = owner;
            this.rawType = rawType;
            this.arguments = arguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType that = (ParameterizedType) other;
            return Objects.equals(owner, that.getOwnerType())
                && Objects.equals(rawType, that.getRawType())
                && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ Objects.hashCode(rawType);
        }

        @Override
        public String getTypeName() {
            return toString();
        }

        @Override
        public String toString() {
            StringBuilder name = new StringBuilder();
            if (owner == null) {
                name.append(rawType.getTypeName());
            } else {
                name.append(owner.getTypeName()).append('$');
                String rawName = rawType.getTypeName();
                Type ownerRawType = owner instanceof ParameterizedType
                    ? ((ParameterizedType) owner).getRawType()
                    : owner;
                String ownerRawName = ownerRawType.getTypeName();
                name.append(rawName.startsWith(ownerRawName + '$')
                    ? rawName.substring(ownerRawName.length() + 1)
                    : rawName);
            }
            if (arguments.length > 0) {
                name.append('<');
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) {
                        name.append(", ");
                    }
                    name.append(arguments[i].getTypeName());
                }
                name.append('>');
            }
            return name.toString();
        }
    }

    private static final class ResolvedGenericArrayType implements GenericArrayType {
        private final Type componentType;

        private ResolvedGenericArrayType(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType
                && componentType.equals(((GenericArrayType) other).getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return componentType.hashCode();
        }

        @Override
        public String getTypeName() {
            return componentType.getTypeName() + "[]";
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }

    private static final class ResolvedWildcardType implements WildcardType {
        private final Type[] upperBounds;
        private final Type[] lowerBounds;

        private ResolvedWildcardType(Type[] upperBounds, Type[] lowerBounds) {
            this.upperBounds = upperBounds.clone();
            this.lowerBounds = lowerBounds.clone();
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WildcardType)) {
                return false;
            }
            WildcardType that = (WildcardType) other;
            return Arrays.equals(upperBounds, that.getUpperBounds())
                && Arrays.equals(lowerBounds, that.getLowerBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(upperBounds) ^ Arrays.hashCode(lowerBounds);
        }

        @Override
        public String getTypeName() {
            return toString();
        }

        @Override
        public String toString() {
            if (lowerBounds.length > 0) {
                return "? super " + boundsTypeName(lowerBounds);
            }
            if (upperBounds.length == 0 || upperBounds[0].equals(Object.class)) {
                return "?";
            }
            return "? extends " + boundsTypeName(upperBounds);
        }

        private static String boundsTypeName(Type[] bounds) {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < bounds.length; i++) {
                if (i > 0) {
                    name.append(" & ");
                }
                name.append(bounds[i].getTypeName());
            }
            return name.toString();
        }
    }
}
