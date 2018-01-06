package ronin.muserver.rest;

import ronin.muserver.Method;

import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

class ResourceClass {

    final UriPattern pathPattern;
    private final Class<?> resourceClass;
    final Object resourceInstance;
    private final List<MediaType> produces;
    Set<ResourceMethod> resourceMethods;
    final String pathTemplate;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Object resourceInstance, List<MediaType> produces) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceInstance.getClass();
        this.resourceInstance = resourceInstance;
        this.produces = produces;
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
            java.lang.reflect.Method annotationSource = JaxMethodLocator.getMethodThatHasJaxRSAnnotations(restMethod);
            Method httpMethod = ResourceMethod.getMuMethod(annotationSource);
            if (httpMethod == null) {
                continue;
            }
            Path methodPath = annotationSource.getAnnotation(Path.class);
            UriPattern pathPattern = methodPath == null ? null : UriPattern.uriTemplateToRegex(methodPath.value());

            Produces methodProducesAnnotation = annotationSource.getAnnotation(Produces.class);
            List<MediaType> methodProduces = methodProducesAnnotation != null
                ? MediaTypeHeaderDelegate.fromStrings(asList(methodProducesAnnotation.value()))
                : this.produces;
            resourceMethods.add(new ResourceMethod(this, pathPattern, restMethod, httpMethod, methodPath == null ? null : methodPath.value(), methodProduces));
        }
        this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
    }

    public static ResourceClass fromObject(Object restResource) {
        Class<?> annotationSource = JaxClassLocator.getClassWithJaxRSAnnotations(restResource.getClass());
        if (annotationSource == null) {
            throw new IllegalArgumentException("The restResource class " + restResource.getClass().getName() + " must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }

        // From section 3.6 of the spec:
        // JAX-RS annotations MAY be used on the methods and method parameters of a super-class or an implemented interface.
        // Such annotations are inherited by a corresponding sub-class or implementation class method provided that method
        // and its parameters do not have any JAX-RS annotations of its own. Annotations on a super-class take precedence
        // over those on an implemented interface. If a subclass or implementation method has any JAX-RS annotations then
        // all of the annotations on the super class or interface method are ignored.

        Path path = annotationSource.getDeclaredAnnotation(Path.class);
        if (path == null) {
            throw new IllegalArgumentException("The class " + annotationSource.getName() + " must specify a " + Path.class.getName()
                + " annotation because it has other JAX RS annotations declared. (Note that @Path cannot be inherited if there are other JAX RS annotations declared on this class.)");
        }
        UriPattern pathPattern = UriPattern.uriTemplateToRegex(path.value());

        Produces produces = annotationSource.getAnnotation(Produces.class);
        List<MediaType> producesList = MediaTypeHeaderDelegate.fromStrings(produces == null ? null : asList(produces.value()));

        ResourceClass resourceClass = new ResourceClass(pathPattern, path.value(), restResource, producesList);
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
