package io.muserver.rest;

import io.muserver.Method;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

import static io.muserver.rest.MethodMapping.jaxToMu;

class ResourceMethod {
    final ResourceClass resourceClass;
    final UriPattern pathPattern;
    final java.lang.reflect.Method methodHandle;
    final Method httpMethod;
    final String pathTemplate;
    final List<MediaType> effectiveConsumes;
    final List<MediaType> directlyProduces;
    final List<MediaType> effectiveProduces;
    final List<ResourceMethodParam> params;
    final DescriptionData descriptionData;
    final boolean isDeprecated;

    ResourceMethod(ResourceClass resourceClass, UriPattern pathPattern, java.lang.reflect.Method methodHandle, List<ResourceMethodParam> params, Method httpMethod, String pathTemplate, List<MediaType> produces, List<MediaType> consumes, DescriptionData descriptionData, boolean isDeprecated) {
        this.resourceClass = resourceClass;
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.params = params;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.directlyProduces = produces;
        this.descriptionData = descriptionData;
        this.isDeprecated = isDeprecated;
        this.effectiveProduces = !produces.isEmpty() ? produces : (!resourceClass.produces.isEmpty() ? resourceClass.produces : RequestMatcher.WILDCARD_AS_LIST);
        this.effectiveConsumes = !consumes.isEmpty() ? consumes : (!resourceClass.consumes.isEmpty() ? resourceClass.consumes : RequestMatcher.WILDCARD_AS_LIST);
    }

    boolean isSubResource() {
        return pathPattern != null;
    }

    boolean isSubResourceLocator() {
        return httpMethod == null;
    }

    Object invoke(Object... params) throws Exception {
        try {
            return methodHandle.invoke(resourceClass.resourceInstance, params);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebApplicationException) {
                throw (WebApplicationException)cause;
            } else {
                throw e;
            }
        }
    }

    static Method getMuMethod(java.lang.reflect.Method restMethod) {
        Annotation[] annotations = restMethod.getAnnotations();
        Method value = null;
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> anno = annotation.annotationType();
            if (anno.getAnnotation(HttpMethod.class) != null) {
                if (value != null) {
                    throw new IllegalArgumentException("The method " + restMethod + " has multiple HttpMethod annotations. Only one is allowed per method.");
                }
                value = jaxToMu(anno);
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "ResourceMethod{" + resourceClass.resourceClassName() + "#" + methodHandle.getName() + "}";
    }

    boolean canProduceFor(List<MediaType> clientAccepts) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveProduces, clientAccepts);
    }

    boolean canConsume(MediaType requestBodyMediaType) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveConsumes, Collections.singletonList(requestBodyMediaType));
    }
}
