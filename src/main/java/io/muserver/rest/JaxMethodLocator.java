package io.muserver.rest;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Annotation;
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
        Method annotated = findAnnotatedMethod(start, concreteClass, concreteClass);
        return annotated == null ? start : annotated;
    }

    private static @Nullable Method findAnnotatedMethod(Method start, Class<?> clazz, Class<?> concreteClass) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }
        Method declared = findAnnotatedMethodOnClass(start, clazz, concreteClass);
        if (declared != null) {
            return declared;
        }
        // Keep the class hierarchy ahead of directly implemented interfaces. If both eventual
        // annotation sources are interfaces, JAX-RS leaves their precedence implementation-specific;
        // this ordering also keeps an inherited method's existing annotation source stable.
        Method inherited = findAnnotatedMethod(start, clazz.getSuperclass(), concreteClass);
        if (inherited != null) {
            return inherited;
        }
        for (Class<?> interfaceClass : clazz.getInterfaces()) {
            inherited = findAnnotatedMethod(start, interfaceClass, concreteClass);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private static @Nullable Method findAnnotatedMethodOnClass(Method start, Class<?> clazz, Class<?> concreteClass) {
        try {
            Method cur = clazz.getDeclaredMethod(start.getName(), start.getParameterTypes());
            if (hasJaxRsAnnotations(cur)) {
                return cur;
            }
        } catch (NoSuchMethodException e) {
            // try parent
        }
        for (Method candidate : clazz.getDeclaredMethods()) {
            if (!candidate.isBridge()
                && hasMatchingResolvedParameters(start, candidate, concreteClass)
                && hasJaxRsAnnotations(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean hasJaxRsAnnotations(Method method) {
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (isJaxRsAnnotation(annotation)) {
                return true;
            }
        }
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (isJaxRsAnnotation(annotation)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isJaxRsAnnotation(Annotation annotation) {
        Class<? extends Annotation> type = annotation.annotationType();
        Package annotationPackage = type.getPackage();
        String packageName = annotationPackage == null ? "" : annotationPackage.getName();
        return (packageName.equals("jakarta.ws.rs") || packageName.startsWith("jakarta.ws.rs."))
            || type.isAnnotationPresent(HttpMethod.class)
            || type.isAnnotationPresent(NameBinding.class);
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
