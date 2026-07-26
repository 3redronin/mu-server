package io.muserver.rest;

import io.muserver.openapi.TagObject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ParamConverterProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

class ResourceClass {

    private static final Logger log = LoggerFactory.getLogger(ResourceClass.class);

    final UriPattern pathPattern;
    final Class<?> resourceClass;
    final @Nullable Object resourceInstance;
    final List<MediaType> produces;
    final List<MediaType> consumes;
    List<ResourceMethod> resourceMethods = Collections.emptyList();
    private boolean methodInfoSet;
    final String pathTemplate;
    final TagObject tag;
    final List<Class<? extends Annotation>> nameBindingAnnotations;
    private final SchemaObjectCustomizer schemaObjectCustomizer;
    final ResourceClassIntrospection introspection;

    /**
     * If this class is sub-resource, then this is the locator method. Otherwise null.
     */
    final @Nullable ResourceMethod locatorMethod;

    private ResourceClass(UriPattern pathPattern, String pathTemplate, Class<?> resourceClass, @Nullable Object resourceInstance, List<MediaType> consumes, List<MediaType> produces, TagObject tag, List<Class<? extends Annotation>> nameBindingAnnotations, SchemaObjectCustomizer schemaObjectCustomizer, @Nullable ResourceMethod locatorMethod) {
        this.pathPattern = pathPattern;
        this.pathTemplate = pathTemplate;
        this.resourceClass = resourceClass;
        this.resourceInstance = resourceInstance;
        this.consumes = consumes;
        this.produces = produces;
        this.tag = tag;
        this.nameBindingAnnotations = nameBindingAnnotations;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.locatorMethod = locatorMethod;
        this.introspection = ResourceClassIntrospection.forClass(resourceClass);
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
        if (methodInfoSet) {
            throw new IllegalStateException("Cannot call setupMethodInfo twice");
        }

        try {
            if (introspection.shouldLogVisibilityWarnings()) {
                introspection.visibilityWarnings.forEach(log::warn);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // A best-effort diagnostic must not prevent an otherwise usable resource class from registering.
        }
        List<ResourceMethod> resourceMethods = new ArrayList<>();
        for (ResourceClassIntrospection.MethodInfo methodInfo : introspection.methods) {
            methodInfo.methodHandle.setAccessible(true);
            List<ResourceMethodParam> params = new ArrayList<>();
            for (ResourceMethodParam.Introspection param : methodInfo.params) {
                params.add(param.bind(paramConverterProviders));
            }
            resourceMethods.add(new ResourceMethod(
                this, methodInfo, params, schemaObjectCustomizer));
        }
        this.resourceMethods = Collections.unmodifiableList(resourceMethods);
        this.methodInfoSet = true;
    }

    static List<String> nonPublicResourceMethodWarnings(Class<?> resourceClass) {
        return ResourceClassIntrospection.forClass(resourceClass).visibilityWarnings;
    }

    static boolean shouldLogVisibilityWarnings(Class<?> resourceClass) {
        return ResourceClassIntrospection.forClass(resourceClass).shouldLogVisibilityWarnings();
    }

    static List<Class<? extends Annotation>> getNameBindingAnnotations(AnnotatedElement annotationSource) {
        return Stream.of(annotationSource.getAnnotations())
            .filter(a -> a.annotationType().isAnnotationPresent(NameBinding.class))
            .map(Annotation::annotationType)
            .collect(toList());
    }

    static ResourceClass fromObject(Object restResource, List<ParamConverterProvider> paramConverterProviders, SchemaObjectCustomizer schemaObjectCustomizer) {
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
            for (Annotation other : annotationSource.getDeclaredAnnotations()) {
                if (other.annotationType().getName().equals("javax.ws.rs.Path")) {
                    throw new IllegalArgumentException("The class " + annotationSource.getName() + " contains an old version " +
                        "of the JAX-RS implementation. The package name for JAX-RS resources has changed from 'javax.ws.rs' to " +
                        "'jakarta.ws.rs' in the 3.0.0 release of jakarta.ws.rs-api. Please change all references in your project to this new namespace in order to " +
                        "use the version of JAX-RS that mu-server implements (this may be as simple as doing a global find and " +
                        "replace for 'javax.ws.rs' to 'jakarta.ws.rs').");
                }
            }
            throw new IllegalArgumentException("The class " + annotationSource.getName() + " must specify a " + Path.class.getName()
                + " annotation because it has other JAX RS annotations declared. (Note that @Path cannot be inherited if there are other JAX RS annotations declared on this class.)");
        }

        UriPattern pathPattern = UriPattern.uriTemplateToRegex(path.value());

        List<MediaType> producesList = getProduces(null, annotationSource);
        List<MediaType> consumesList = getConsumes(null, annotationSource);
        List<Class<? extends Annotation>> classLevelNameBindingAnnotations = getNameBindingAnnotations(annotationSource);

        TagObject tag = DescriptionData.fromAnnotation(annotationSource, annotationSource.getSimpleName()).toTag();
        ResourceClass resourceClass = new ResourceClass(pathPattern, path.value(), restResource.getClass(), restResource, consumesList, producesList, tag, classLevelNameBindingAnnotations, schemaObjectCustomizer, null);
        resourceClass.setupMethodInfo(paramConverterProviders);
        return resourceClass;
    }

    private static List<MediaType> getProduces(@Nullable List<MediaType> existing, Class<?> annotationSource) {
        Produces produces = annotationSource.getAnnotation(Produces.class);
        List<MediaType> producesList = new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(produces == null ? null : asList(produces.value())));
        if (existing != null) {
            producesList.addAll(existing);
        }
        return producesList;
    }

    private static List<MediaType> getConsumes(@Nullable List<MediaType> existing, Class<?> annotationSource) {
        Consumes consumes = annotationSource.getAnnotation(Consumes.class);
        List<MediaType> consumesList = new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(consumes == null ? null : asList(consumes.value())));
        if (existing != null) {
            consumesList.addAll(existing);
        }
        return consumesList;
    }

    static ResourceClass forSubResourceLocator(ResourceMethod rm, Class<?> instanceClass, @Nullable Object instance, SchemaObjectCustomizer schemaObjectCustomizer, List<ParamConverterProvider> paramConverterProviders) {
        @Nullable List<MediaType> existingConsumes = rm.effectiveConsumes.isEmpty() || (rm.directlyConsumes().isEmpty() && rm.effectiveConsumes.size() == 1 && rm.effectiveConsumes.get(0) == MediaType.WILDCARD_TYPE) ? null : rm.effectiveConsumes;
        List<MediaType> consumes = getConsumes(existingConsumes, instanceClass);
        @Nullable List<MediaType> existingProduces = rm.effectiveProduces.isEmpty() || (rm.directlyProduces().isEmpty() && rm.effectiveProduces.size() == 1 && rm.effectiveProduces.get(0) == MediaType.WILDCARD_TYPE) ? null : rm.effectiveProduces;
        List<MediaType> produces = getProduces(existingProduces, instanceClass);
        ResourceClass resourceClass = new ResourceClass(
            java.util.Objects.requireNonNull(rm.pathPattern(), "Sub-resource locator had no path pattern"),
            java.util.Objects.requireNonNull(rm.pathTemplate(), "Sub-resource locator had no path template"),
            instanceClass, instance, consumes, produces, rm.resourceClass.tag,
            rm.resourceClass.nameBindingAnnotations, schemaObjectCustomizer, rm);
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

    Object requiredResourceInstance() {
        return java.util.Objects.requireNonNull(resourceInstance, "Resource instance is not available");
    }
}
