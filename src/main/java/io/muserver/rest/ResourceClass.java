package io.muserver.rest;

import io.muserver.Method;
import io.muserver.openapi.TagObject;

import javax.ws.rs.Consumes;
import javax.ws.rs.NameBinding;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

class ResourceClass {

    final UriPattern pathPattern;
    private final Class<?> resourceClass;
    final Object resourceInstance;
    final List<MediaType> produces;
    final List<MediaType> consumes;
    Set<ResourceMethod> resourceMethods;
    final String pathTemplate;
    final TagObject tag;
    final List<Class<? extends Annotation>> nameBindingAnnotations;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Object resourceInstance, List<MediaType> consumes, List<MediaType> produces, TagObject tag, List<Class<? extends Annotation>> nameBindingAnnotations) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceInstance.getClass();
        this.resourceInstance = resourceInstance;
        this.consumes = consumes;
        this.produces = produces;
        this.tag = tag;
        this.nameBindingAnnotations = nameBindingAnnotations;
    }

    public boolean matches(URI uri) {
        return pathPattern.matcher(uri).prefixMatches();
    }

    Set<ResourceMethod> nonSubResourceMethods() {
        return resourceMethods.stream().filter(resourceMethod -> !resourceMethod.isSubResource()).collect(Collectors.toSet());
    }

    Set<ResourceMethod> subResourceMethods() {
        return resourceMethods.stream().filter(ResourceMethod::isSubResource).collect(Collectors.toSet());
    }

    private void setupMethodInfo(List<ParamConverterProvider> paramConverterProviders) {
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
            restMethod.setAccessible(true);
            Path methodPath = annotationSource.getAnnotation(Path.class);

            List<Class<? extends Annotation>> methodNameBindingAnnotations = getNameBindingAnnotations(annotationSource);

            UriPattern pathPattern = methodPath == null ? null : UriPattern.uriTemplateToRegex(methodPath.value());

            List<MediaType> methodProduces = MediaTypeDeterminer.supportedProducesTypes(annotationSource);
            List<MediaType> methodConsumes = MediaTypeDeterminer.supportedConsumesTypes(annotationSource);
            List<ResourceMethodParam> params = new ArrayList<>();
            Parameter[] parameters = annotationSource.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                Parameter p = parameters[i];
                ResourceMethodParam resourceMethodParam = ResourceMethodParam.fromParameter(i, p, paramConverterProviders);
                params.add(resourceMethodParam);
            }
            DescriptionData descriptionData = DescriptionData.fromAnnotation(restMethod, null);
            String pathTemplate = methodPath == null ? null : methodPath.value();
            boolean isDeprecated = annotationSource.isAnnotationPresent(Deprecated.class);
            resourceMethods.add(new ResourceMethod(this, pathPattern, restMethod, params, httpMethod, pathTemplate, methodProduces, methodConsumes, descriptionData, isDeprecated, methodNameBindingAnnotations));
        }
        this.resourceMethods = Collections.unmodifiableSet(resourceMethods);
    }

    static List<Class<? extends Annotation>> getNameBindingAnnotations(AnnotatedElement annotationSource) {
        return Stream.of(annotationSource.getAnnotations())
            .filter(a -> a.annotationType().isAnnotationPresent(NameBinding.class))
            .map(Annotation::annotationType)
            .collect(toList());
    }

    static ResourceClass fromObject(Object restResource, List<ParamConverterProvider> paramConverterProviders) {
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

        Consumes consumes = annotationSource.getAnnotation(Consumes.class);
        List<MediaType> consumesList = MediaTypeHeaderDelegate.fromStrings(consumes == null ? null : asList(consumes.value()));

        List<Class<? extends Annotation>> classLevelNameBindingAnnotations = getNameBindingAnnotations(annotationSource);

        TagObject tag = DescriptionData.fromAnnotation(annotationSource, annotationSource.getSimpleName()).toTag();
        ResourceClass resourceClass = new ResourceClass(pathPattern, path.value(), restResource, consumesList, producesList, tag, classLevelNameBindingAnnotations);
        resourceClass.setupMethodInfo(paramConverterProviders);
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
