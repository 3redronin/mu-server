package io.muserver.rest;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import org.jspecify.annotations.Nullable;

/**
 * Given a Method reference, finds the actual method to load jax-rs annotations from, following the spec section 3.6
 */
class JaxMethodLocator {
    static Method getMethodThatHasJaxRSAnnotations(Method start) {
        return getMethodThatHasJaxRSAnnotations(start, start.getDeclaringClass());
    }

    static Method getMethodThatHasJaxRSAnnotations(Method start, Class<?> concreteClass) {
        Class<?> clazz = start.getDeclaringClass();
        while (clazz != Object.class && clazz != null) {
            Method cur = getMethodIfGood(start, clazz, concreteClass);
            if (cur != null) {
                return cur;
            }
            clazz = clazz.getSuperclass();
        }
        clazz = start.getDeclaringClass();
        while (clazz != Object.class && clazz != null) {
            for (Class<?> interfaceClass : clazz.getInterfaces()) {
                Method cur = getMethodIfGood(start, interfaceClass, concreteClass);
                if (cur != null) {
                    return cur;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return start;
    }

    private static @Nullable Method getMethodIfGood(Method start, Class<?> clazz, Class<?> concreteClass) {
        try {
            Method cur = clazz.getDeclaredMethod(start.getName(), start.getParameterTypes());
            if (JaxClassLocator.hasAtLeastOneJaxRSAnnotation(cur.getDeclaredAnnotations())) {
                return cur;
            }
        } catch (NoSuchMethodException e) {
            // try parent
        }
        for (Method candidate : clazz.getDeclaredMethods()) {
            if (hasMatchingResolvedParameters(start, candidate, concreteClass)
                && JaxClassLocator.hasAtLeastOneJaxRSAnnotation(candidate.getDeclaredAnnotations())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasMatchingResolvedParameters(Method start, Method candidate, Class<?> concreteClass) {
        if (!candidate.getName().equals(start.getName()) || candidate.getParameterCount() != start.getParameterCount()) {
            return false;
        }
        Type[] startParameters = start.getGenericParameterTypes();
        Type[] candidateParameters = candidate.getGenericParameterTypes();
        for (int i = 0; i < startParameters.length; i++) {
            Type resolvedStart = GenericTypeResolver.resolve(startParameters[i], concreteClass, start.getDeclaringClass());
            Type resolvedCandidate = GenericTypeResolver.resolve(candidateParameters[i], concreteClass, candidate.getDeclaringClass());
            if (!resolvedStart.equals(resolvedCandidate)) {
                return false;
            }
        }
        return true;
    }

}
