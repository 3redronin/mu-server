package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.Path;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

class ResourceClass {

    final UriPattern pathPattern;
    private final Class<?> resourceClass;
    final Object resourceInstance;
    Set<ResourceMethod> resourceMethods;
    final String pathTemplate;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Object resourceInstance) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceInstance.getClass();
        this.resourceInstance = resourceInstance;
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
        java.lang.reflect.Method[] methods = this.resourceClass.getMethods();
        for (java.lang.reflect.Method restMethod : methods) {
            Method httpMethod = ResourceMethod.getMuMethod(restMethod);
            if (httpMethod == null) {
                continue;
            }
            Path methodPath = restMethod.getAnnotation(Path.class);
            UriPattern pathPattern = methodPath == null ? null : UriPattern.uriTemplateToRegex(methodPath.value());
            resourceMethods.add(new ResourceMethod(this, pathPattern, restMethod, httpMethod, methodPath == null ? null : methodPath.value()));
        }
        this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
    }

    public static ResourceClass fromObject(Object restResource) {
        Class<?> clazz = JaxClassLocator.getClassWithJaxRSAnnotations(restResource.getClass());
        if (clazz == null) {
            throw new IllegalArgumentException("The restResource class " + restResource.getClass().getName() + " must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }

        // From section 3.6 of the spec:
        // JAX-RS annotations MAY be used on the methods and method parameters of a super-class or an implemented interface.
        // Such annotations are inherited by a corresponding sub-class or implementation class method provided that method
        // and its parameters do not have any JAX-RS annotations of its own. Annotations on a super-class take precedence
        // over those on an implemented interface. If a subclass or implementation method has any JAX-RS annotations then
        // all of the annotations on the super class or interface method are ignored.

        Path path = clazz.getDeclaredAnnotation(Path.class);
        if (path == null) {
            throw new IllegalArgumentException("The class " + clazz.getName() + " must specify a " + Path.class.getName()
                + " annotation because it has other JAX RS annotations declared. (Note that @Path cannot be inherited if there are other JAX RS annotations declared on this class.)");
        }
        UriPattern pathPattern = UriPattern.uriTemplateToRegex(path.value());
        ResourceClass resourceClass = new ResourceClass(pathPattern, path.value(), restResource);
        resourceClass.setupMethodInfo();
        return resourceClass;
    }


    @Override
    public String toString() {
        return "ResourceClass{" + resourceClassName() + '}';
    }

    String resourceClassName() {
        return resourceClass.getName();
    }
}
