package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.net.URI;

public class RestHandler {

    private final Object restResource;
    private final Class<? extends Object> resourceClass;
    private final Path path;

    public RestHandler(Object restResource) {
        this.restResource = restResource;
        this.resourceClass = restResource.getClass();
        this.path = resourceClass.getAnnotation(Path.class);
        if (path == null) {
            throw new IllegalArgumentException("The restResource must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }
    }

    public boolean matches(Method method, URI requestUri) {

        java.lang.reflect.Method[] methods = resourceClass.getMethods();
        if (!requestUri.getPath().startsWith(path.value())) {
            return false;
        }
        Class<? extends Annotation> jaxMethod = MethodMapping.toJaxMethod(method);
        for (java.lang.reflect.Method restMethod : methods) {
            Annotation methodAnnotation = restMethod.getAnnotation(jaxMethod);
            if (methodAnnotation != null) {
                return true;
            }
        }

        return false;
    }
}
