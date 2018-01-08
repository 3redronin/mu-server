package io.muserver.rest;

import java.lang.reflect.Method;

/**
 * Given a Method reference, finds the actual method to load jax-rs annotations from, following the spec section 3.6
 */
class JaxMethodLocator {
    static Method getMethodThatHasJaxRSAnnotations(Method start) {
        Class<?> clazz = start.getDeclaringClass();
        while (clazz != Object.class) {
            Method cur = getMethodIfGood(start, clazz);
            if (cur != null) {
                return cur;
            }
            clazz = clazz.getSuperclass();
        }
        clazz = start.getDeclaringClass();
        while (clazz != Object.class) {
            for (Class<?> interfaceClass : clazz.getInterfaces()) {
                Method cur = getMethodIfGood(start, interfaceClass);
                if (cur != null) {
                    return cur;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return start;
    }

    private static Method getMethodIfGood(Method start, Class<?> clazz) {
        try {
            Method cur = clazz.getDeclaredMethod(start.getName(), start.getParameterTypes());
            if (JaxClassLocator.hasAtLeastOneJaxRSAnnotation(cur.getDeclaredAnnotations())) {
                return cur;
            }
        } catch (NoSuchMethodException e) {
            // try parent
        }
        return null;
    }


}
