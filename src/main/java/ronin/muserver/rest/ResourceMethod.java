package ronin.muserver.rest;

import ronin.muserver.Method;
import ronin.muserver.MuRequest;

import javax.ws.rs.HttpMethod;
import java.lang.annotation.Annotation;

import static ronin.muserver.rest.MethodMapping.jaxToMu;

class ResourceMethod {
    private final ResourceClass resourceClass;
    final UriPattern pathPattern;
    final java.lang.reflect.Method methodHandle;
    final Method httpMethod;
    final String pathTemplate;

    public ResourceMethod(ResourceClass resourceClass, UriPattern pathPattern, java.lang.reflect.Method methodHandle, Method httpMethod, String pathTemplate) {
        this.resourceClass = resourceClass;
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
    }

    public boolean isSubResource() {
        return pathPattern != null;
    }

    public boolean matches(MuRequest request) {
        if (request.method() != httpMethod) {
            return false;
        }
        PathMatch matcher = pathPattern.matcher(request.uri());
        return matcher.matches();
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
}
