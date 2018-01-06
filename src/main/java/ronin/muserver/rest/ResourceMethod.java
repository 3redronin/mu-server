package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

import static ronin.muserver.rest.MethodMapping.jaxToMu;

class ResourceMethod {
    private final ResourceClass resourceClass;
    final UriPattern pathPattern;
    final java.lang.reflect.Method methodHandle;
    final Method httpMethod;
    final String pathTemplate;
    private final List<MediaType> produces;
    private final List<MediaType> consumes;

    public ResourceMethod(ResourceClass resourceClass, UriPattern pathPattern, java.lang.reflect.Method methodHandle, Method httpMethod, String pathTemplate, List<MediaType> produces, List<MediaType> consumes) {
        this.resourceClass = resourceClass;
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.produces = produces;
        this.consumes = consumes;
    }

    public boolean isSubResource() {
        return pathPattern != null;
    }
    public boolean isSubResourceLocator() {
        return httpMethod == null;
    }

    public Object invoke(Object... params) throws InvocationTargetException, IllegalAccessException {
        Object result = methodHandle.invoke(resourceClass.resourceInstance, params);
        return result;
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

    public boolean canProduceFor(List<MediaType> clientAccepts) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(produces, clientAccepts);
    }

    public boolean canConsume(MediaType requestBodyMediaType) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(consumes, Collections.singletonList(requestBodyMediaType));
    }
}
