package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

class ResourceClass {

    final UriPattern pathPattern;
    private final Class<?> resourceClass;
    Set<ResourceMethod> resourceMethods;
    final String pathTemplate;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Class<?> resourceClass) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceClass;
    }

    public boolean matches(URI uri) {
        return pathPattern.matcher(uri).matches();
    }

    public Set<ResourceMethod> nonSubResourceMethods() {
        return resourceMethods.stream().filter(resourceMethod -> !resourceMethod.isSubResource()).collect(Collectors.toSet());
    }
    public Set<ResourceMethod> subResourceMethods() {
        return resourceMethods.stream().filter(ResourceMethod::isSubResource).collect(Collectors.toSet());
    }

    private void setupMethodInfo() {
        if (resourceMethods != null) {
            throw new IllegalStateException("Cannot call setupMethodInfo twice");
        }
        Set<ResourceMethod> resourceMethods = new HashSet<>();
        java.lang.reflect.Method[] methods = this.resourceClass.getClass().getMethods();
        for (java.lang.reflect.Method restMethod : methods) {
            Method httpMethod = ResourceMethod.getMuMethod(restMethod);
            if (httpMethod == null) {
                continue;
            }
            Path methodPath = httpMethod.getClass().getAnnotation(Path.class);
            UriPattern pathPattern = methodPath == null ? null : UriPattern.uriTemplateToRegex(methodPath.value());
            resourceMethods.add(new ResourceMethod(this, pathPattern, restMethod, httpMethod, methodPath == null ? null : methodPath.value()));
        }
        this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
    }

    public static ResourceClass fromObject(Object restResource) {
        Class<?> clazz = restResource.getClass();
        ResourceClass resourceClass = null;
        while (clazz != null) {
            // From section 3.6 of the spec:
            // JAX-RS annotations MAY be used on the methods and method parameters of a super-class or an implemented interface.
            // Such annotations are inherited by a corresponding sub-class or implementation class method provided that method
            // and its parameters do not have any JAX-RS annotations of its own. Annotations on a super-class take precedence
            // over those on an implemented interface. If a subclass or implementation method has any JAX-RS annotations then
            // all of the annotations on the super class or interface method are ignored.

            boolean hasAJaxAnnotation = hasAtLeastOneJaxRSAnnotation(clazz.getAnnotations());
            if (!hasAJaxAnnotation) {
                clazz = clazz.getSuperclass();
            } else {
                Path path = clazz.getDeclaredAnnotation(Path.class);
                if (path == null) {
                    throw new IllegalArgumentException("The class " + clazz.getName() + " must specify a " + Path.class.getName()
                        + " annotation because it has other JAX RS annotations declared. (Note that @Path cannot be inherited if there are other JAX RS annotations declared on this class.)");
                }
                UriPattern pathPattern = UriPattern.uriTemplateToRegex(path.value());
                resourceClass = new ResourceClass(pathPattern, path.value(), clazz);
                resourceClass.setupMethodInfo();
                break;
            }
        }
        if (resourceClass == null) {
            throw new IllegalArgumentException("The restResource class " + restResource.getClass().getName() + " must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }
        return resourceClass;
    }

    private static boolean hasAtLeastOneJaxRSAnnotation(Annotation[] annotations) {
        boolean hasAJaxAnnotation = false;
        for (Annotation annotation : annotations) {
            String packageName = annotation.annotationType().getPackage().getName();
            if (packageName.equals("javax.ws.rs") || packageName.startsWith("javax.ws.rs.")) {
                hasAJaxAnnotation = true;
            }
        }
        return hasAJaxAnnotation;
    }

    @Override
    public String toString() {
        return "ResourceClass{" + resourceClass.getName() + '}';
    }
}
