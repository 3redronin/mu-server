package io.muserver.rest;

import java.lang.annotation.Annotation;

/**
 * Given the class of a rest resource, this finds the class to get the JAX-RS annotations from.
 * As per the spec, the class and its super classes are checked before interfaces.
 */
class JaxClassLocator {
    static Class<?> getClassWithJaxRSAnnotations(Class<?> start) {
        Class<?> clazz = start;
        while (clazz != Object.class) {
            if (hasAtLeastOneJaxRSAnnotation(clazz.getDeclaredAnnotations())) {
                return clazz;
            }
            clazz = clazz.getSuperclass();
        }
        clazz = start;
        while (clazz != Object.class) {
            for (Class<?> interfaceClass : clazz.getInterfaces()) {
                if (hasAtLeastOneJaxRSAnnotation(interfaceClass.getDeclaredAnnotations())) {
                    return interfaceClass;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    static boolean hasAtLeastOneJaxRSAnnotation(Annotation[] annotations) {
        boolean hasAJaxAnnotation = false;
        for (Annotation annotation : annotations) {
            String packageName = annotation.annotationType().getPackage().getName();
            if (packageName.equals("javax.ws.rs") || packageName.startsWith("javax.ws.rs.")) {
                hasAJaxAnnotation = true;
            }
        }
        return hasAJaxAnnotation;
    }
}
