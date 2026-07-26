package io.muserver.rest;

import io.muserver.Method;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable metadata that is derived solely from a concrete resource class.
 */
final class ResourceClassIntrospection {
    private static final ClassValue<ResourceClassIntrospection> CACHE = new ClassValue<ResourceClassIntrospection>() {
        @Override
        protected ResourceClassIntrospection computeValue(Class<?> type) {
            return introspect(type);
        }
    };

    final List<MethodInfo> methods;
    final List<String> visibilityWarnings;

    private ResourceClassIntrospection(List<MethodInfo> methods, List<String> visibilityWarnings) {
        this.methods = Collections.unmodifiableList(new ArrayList<>(methods));
        this.visibilityWarnings = Collections.unmodifiableList(new ArrayList<>(visibilityWarnings));
    }

    static ResourceClassIntrospection forClass(Class<?> resourceClass) {
        // Media type annotation processing uses Jakarta REST's RuntimeDelegate. Keep the
        // one-time global delegate setup outside computeValue, which must stay side-effect free.
        MuRuntimeDelegate.ensureSet();
        return CACHE.get(resourceClass);
    }

    private static ResourceClassIntrospection introspect(Class<?> resourceClass) {
        List<MethodInfo> methods = new ArrayList<>();
        for (java.lang.reflect.Method restMethod : resourceClass.getMethods()) {
            if (restMethod.isBridge()) {
                continue;
            }
            java.lang.reflect.Method annotationSource =
                JaxMethodLocator.getMethodThatHasJaxRSAnnotations(restMethod, resourceClass);
            @Nullable Method httpMethod = ResourceMethod.getMuMethod(annotationSource);
            Path methodPath = annotationSource.getAnnotation(Path.class);
            if (methodPath == null && httpMethod == null) {
                continue;
            }

            @Nullable String pathTemplate = methodPath == null ? null : methodPath.value();
            @Nullable UriPattern methodPattern =
                pathTemplate == null ? null : UriPattern.uriTemplateToRegex(pathTemplate);
            Parameter[] annotationParameters = annotationSource.getParameters();
            Parameter[] methodParameters = restMethod.getParameters();
            List<ResourceMethodParam.Introspection> params = new ArrayList<>(annotationParameters.length);
            for (int i = 0; i < annotationParameters.length; i++) {
                params.add(ResourceMethodParam.introspect(
                    i, methodParameters[i], annotationParameters[i], resourceClass, methodPattern));
            }

            methods.add(new MethodInfo(
                restMethod,
                annotationSource,
                methodPattern,
                httpMethod,
                pathTemplate,
                MediaTypeDeterminer.supportedProducesTypes(annotationSource),
                MediaTypeDeterminer.supportedConsumesTypes(annotationSource),
                params,
                GenericTypeResolver.resolveConcrete(
                    restMethod.getGenericReturnType(), resourceClass, restMethod.getDeclaringClass()),
                annotationSource.isAnnotationPresent(Deprecated.class),
                ResourceClass.getNameBindingAnnotations(annotationSource),
                Arrays.asList(annotationSource.getAnnotations())
            ));
        }
        return new ResourceClassIntrospection(methods, visibilityWarnings(resourceClass));
    }

    private static List<String> visibilityWarnings(Class<?> resourceClass) {
        List<String> warnings = new ArrayList<>();
        try {
            for (Class<?> current = resourceClass;
                 current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (java.lang.reflect.Method method : current.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers()) && isResourceMethodOrLocator(method)) {
                        warnings.add("The JAX-RS annotated method " + method.toGenericString()
                            + " cannot itself be exposed as a resource method or sub-resource locator because only public methods may be exposed.");
                    }
                }
            }
            Collections.sort(warnings);
        } catch (LinkageError | RuntimeException ignored) {
            // A best-effort diagnostic must not prevent an otherwise usable resource class from registering.
            warnings.clear();
        }
        return warnings;
    }

    private static boolean isResourceMethodOrLocator(java.lang.reflect.Method method) {
        if (method.isAnnotationPresent(Path.class)) {
            return true;
        }
        for (Annotation annotation : method.getDeclaredAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }

    static final class MethodInfo {
        final java.lang.reflect.Method methodHandle;
        final java.lang.reflect.Method annotationSource;
        final @Nullable UriPattern pathPattern;
        final @Nullable Method httpMethod;
        final @Nullable String pathTemplate;
        final List<MediaType> directlyProduces;
        final List<MediaType> directlyConsumes;
        final List<ResourceMethodParam.Introspection> params;
        final @Nullable Type genericReturnType;
        final boolean deprecated;
        final List<Class<? extends Annotation>> nameBindingAnnotations;
        final List<Annotation> methodAnnotations;

        private MethodInfo(java.lang.reflect.Method methodHandle,
                           java.lang.reflect.Method annotationSource,
                           @Nullable UriPattern pathPattern,
                           @Nullable Method httpMethod,
                           @Nullable String pathTemplate,
                           List<MediaType> directlyProduces,
                           List<MediaType> directlyConsumes,
                           List<ResourceMethodParam.Introspection> params,
                           @Nullable Type genericReturnType,
                           boolean deprecated,
                           List<Class<? extends Annotation>> nameBindingAnnotations,
                           List<Annotation> methodAnnotations) {
            this.methodHandle = methodHandle;
            this.annotationSource = annotationSource;
            this.pathPattern = pathPattern;
            this.httpMethod = httpMethod;
            this.pathTemplate = pathTemplate;
            this.directlyProduces = immutableCopy(directlyProduces);
            this.directlyConsumes = immutableCopy(directlyConsumes);
            this.params = immutableCopy(params);
            this.genericReturnType = genericReturnType;
            this.deprecated = deprecated;
            this.nameBindingAnnotations = immutableCopy(nameBindingAnnotations);
            this.methodAnnotations = immutableCopy(methodAnnotations);
        }

        private static <T> List<T> immutableCopy(List<T> values) {
            return Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
