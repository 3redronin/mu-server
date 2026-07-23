package io.muserver.rest;

import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ApplicationRegistrar {

    private ApplicationRegistrar() {
    }

    @SuppressWarnings("deprecation") // Jakarta REST 3.1 deprecates registration, but still requires it to be honored.
    static RestHandlerBuilder from(Application application) {
        return from(application, false);
    }

    @SuppressWarnings("deprecation") // Jakarta REST 3.1 deprecates registration, but still requires it to be honored.
    static RestHandlerBuilder from(Application application, boolean applicationPathHandledExternally) {
        Objects.requireNonNull(application, "application");
        if (!applicationPathHandledExternally
            && application.getClass().isAnnotationPresent(ApplicationPath.class)) {
            throw new UnsupportedOperationException("@ApplicationPath cannot be applied by a RestHandlerBuilder. "
                + "Mount the handler with ContextHandlerBuilder or start the application with SeBootstrap.");
        }
        rejectProperties(application.getProperties());

        Set<Object> singletons = copyOf(application.getSingletons());
        validateUniqueSingletonClasses(singletons);
        Set<Class<?>> classes = copyOf(application.getClasses());

        List<Object> components = new ArrayList<>(singletons);
        for (Class<?> componentClass : classes) {
            if (containsInstanceOf(singletons, componentClass) || isClientOnly(componentClass)) {
                continue;
            }
            if (isRootResource(componentClass)) {
                throw new UnsupportedOperationException("The Application class " + componentClass.getName()
                    + " is a per-request resource, but Mu Server only supports singleton resource instances. "
                    + "Return an instance from Application.getSingletons() instead.");
            }
            if (!isSupportedComponentType(componentClass)) {
                throw unsupported(componentClass);
            }
            components.add(instantiate(componentClass));
        }

        RestHandlerBuilder builder = new RestHandlerBuilder();
        List<Object> serverComponents = new ArrayList<>();
        for (Object component : components) {
            Objects.requireNonNull(component, "Application components must not contain null");
            if (!isClientOnly(component.getClass())) {
                register(builder, component);
                serverComponents.add(component);
            }
        }
        registerFiltersAndInterceptors(builder, application, serverComponents);
        return builder;
    }

    private static void rejectProperties(Map<String, Object> properties) {
        if (properties != null && !properties.isEmpty()) {
            throw new UnsupportedOperationException("Application properties are not supported by Mu Server");
        }
    }

    private static <T> Set<T> copyOf(Set<T> source) {
        return source == null ? new LinkedHashSet<>() : new LinkedHashSet<>(source);
    }

    private static void validateUniqueSingletonClasses(Set<Object> singletons) {
        Set<Class<?>> classes = new HashSet<>();
        for (Object singleton : singletons) {
            Objects.requireNonNull(singleton, "Application.getSingletons() must not contain null");
            if (!classes.add(singleton.getClass())) {
                throw new IllegalArgumentException("Application.getSingletons() returned more than one singleton of "
                    + singleton.getClass().getName());
            }
        }
    }

    private static boolean containsInstanceOf(Set<Object> singletons, Class<?> componentClass) {
        for (Object singleton : singletons) {
            if (singleton.getClass().equals(componentClass)) {
                return true;
            }
        }
        return false;
    }

    private static Object instantiate(Class<?> componentClass) {
        try {
            return componentClass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not instantiate Application provider " + componentClass.getName()
                + " using a public no-argument constructor", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void register(RestHandlerBuilder builder, Object component) {
        boolean registered = false;
        if (isRootResource(component.getClass())) {
            builder.addResource(component);
            registered = true;
        }
        if (component instanceof MessageBodyReader) {
            builder.addCustomReader((MessageBodyReader) component);
            registered = true;
        }
        if (component instanceof MessageBodyWriter) {
            builder.addCustomWriter((MessageBodyWriter) component);
            registered = true;
        }
        if (component instanceof ParamConverterProvider) {
            builder.addCustomParamConverterProvider((ParamConverterProvider) component);
            registered = true;
        }
        if (component instanceof ExceptionMapper) {
            Type exceptionType = GenericTypeResolver.resolveTypeArgument(component.getClass(), ExceptionMapper.class, 0);
            if (!(exceptionType instanceof Class) || !Throwable.class.isAssignableFrom((Class<?>) exceptionType)) {
                throw new IllegalArgumentException("Could not infer the exception type handled by "
                    + component.getClass().getName());
            }
            builder.addExceptionMapper((Class<? extends Throwable>) exceptionType, (ExceptionMapper) component);
            registered = true;
        }
        if (component instanceof SchemaObjectCustomizer) {
            builder.addSchemaObjectCustomizer((SchemaObjectCustomizer) component);
            registered = true;
        }
        if (component instanceof ContainerRequestFilter
            || component instanceof ContainerResponseFilter
            || component instanceof ReaderInterceptor
            || component instanceof WriterInterceptor) {
            registered = true;
        }
        if (!registered) {
            throw unsupported(component.getClass());
        }
    }

    private static void registerFiltersAndInterceptors(RestHandlerBuilder builder, Application application,
                                                       List<Object> components) {
        for (Object component : components) {
            if (component instanceof ContainerRequestFilter) {
                ContainerRequestFilter filter = (ContainerRequestFilter) component;
                builder.addRequestFilter(component.getClass().isAnnotationPresent(PreMatching.class)
                    ? filter
                    : new BoundRequestFilter(filter, remainingBindings(application, component)));
            }
        }
        for (Object component : components) {
            if (component instanceof ContainerResponseFilter) {
                builder.addResponseFilter(new BoundResponseFilter((ContainerResponseFilter) component,
                    remainingBindings(application, component)));
            }
        }
        for (Object component : components) {
            if (component instanceof ReaderInterceptor) {
                builder.addReaderInterceptor(new BoundReaderInterceptor((ReaderInterceptor) component,
                    remainingBindings(application, component)));
            }
        }
        for (Object component : components) {
            if (component instanceof WriterInterceptor) {
                builder.addWriterInterceptor(new BoundWriterInterceptor((WriterInterceptor) component,
                    remainingBindings(application, component)));
            }
        }
    }

    private static Set<Class<? extends Annotation>> remainingBindings(Application application, Object component) {
        Set<Class<? extends Annotation>> remaining = nameBindings(component.getClass());
        remaining.removeAll(nameBindings(application.getClass()));
        return remaining;
    }

    private static Set<Class<? extends Annotation>> nameBindings(Class<?> type) {
        Set<Class<? extends Annotation>> result = new LinkedHashSet<>();
        for (Annotation annotation : type.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(NameBinding.class)) {
                result.add(annotation.annotationType());
            }
        }
        return result;
    }

    private static ResourceInfo resourceInfo(Object value) {
        return value instanceof ResourceInfo ? (ResourceInfo) value : null;
    }

    private static boolean hasBindings(ResourceInfo resourceInfo, Set<Class<? extends Annotation>> requiredBindings) {
        if (requiredBindings.isEmpty()) {
            return true;
        }
        if (resourceInfo == null || resourceInfo.getResourceMethod() == null || resourceInfo.getResourceClass() == null) {
            return false;
        }
        Method concreteMethod = resourceInfo.getResourceMethod();
        Class<?> concreteClass = resourceInfo.getResourceClass();
        for (Class<? extends Annotation> binding : requiredBindings) {
            if (!concreteMethod.isAnnotationPresent(binding)
                && !JaxMethodLocator.getMethodThatHasJaxRSAnnotations(concreteMethod, concreteClass)
                    .isAnnotationPresent(binding)
                && !concreteClass.isAnnotationPresent(binding)
                && !classAnnotationSource(concreteClass).isAnnotationPresent(binding)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> classAnnotationSource(Class<?> start) {
        Class<?> source = JaxClassLocator.getClassWithJaxRSAnnotations(start);
        return source == null ? start : source;
    }

    private static boolean isRootResource(Class<?> componentClass) {
        Class<?> annotationSource = JaxClassLocator.getClassWithJaxRSAnnotations(componentClass);
        return annotationSource != null && annotationSource.isAnnotationPresent(Path.class);
    }

    private static boolean isSupportedComponentType(Class<?> componentClass) {
        return MessageBodyReader.class.isAssignableFrom(componentClass)
            || MessageBodyWriter.class.isAssignableFrom(componentClass)
            || ParamConverterProvider.class.isAssignableFrom(componentClass)
            || ExceptionMapper.class.isAssignableFrom(componentClass)
            || ContainerRequestFilter.class.isAssignableFrom(componentClass)
            || ContainerResponseFilter.class.isAssignableFrom(componentClass)
            || ReaderInterceptor.class.isAssignableFrom(componentClass)
            || WriterInterceptor.class.isAssignableFrom(componentClass)
            || SchemaObjectCustomizer.class.isAssignableFrom(componentClass);
    }

    private static boolean isClientOnly(Class<?> componentClass) {
        ConstrainedTo constrainedTo = componentClass.getAnnotation(ConstrainedTo.class);
        return constrainedTo != null && constrainedTo.value() == RuntimeType.CLIENT;
    }

    private static IllegalArgumentException unsupported(Class<?> componentClass) {
        return new IllegalArgumentException("Unsupported Application component " + componentClass.getName());
    }

    private abstract static class BoundComponent implements PrioritizedComponent {
        private final Set<Class<? extends Annotation>> bindings;
        private final int priority;

        BoundComponent(Object delegate, Set<Class<? extends Annotation>> bindings) {
            this.bindings = bindings;
            this.priority = PrioritizedComponent.priorityOf(delegate);
        }

        final boolean matches(Object resourceInfo) {
            return hasBindings(ApplicationRegistrar.resourceInfo(resourceInfo), bindings);
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    private static final class BoundRequestFilter extends BoundComponent implements ContainerRequestFilter {
        private final ContainerRequestFilter delegate;

        BoundRequestFilter(ContainerRequestFilter delegate, Set<Class<? extends Annotation>> bindings) {
            super(delegate, bindings);
            this.delegate = delegate;
        }

        @Override
        public void filter(ContainerRequestContext context) throws IOException {
            if (matches(context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY))) {
                delegate.filter(context);
            }
        }
    }

    private static final class BoundResponseFilter extends BoundComponent implements ContainerResponseFilter {
        private final ContainerResponseFilter delegate;

        BoundResponseFilter(ContainerResponseFilter delegate, Set<Class<? extends Annotation>> bindings) {
            super(delegate, bindings);
            this.delegate = delegate;
        }

        @Override
        public void filter(ContainerRequestContext requestContext,
                           ContainerResponseContext responseContext) throws IOException {
            if (matches(requestContext.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY))) {
                delegate.filter(requestContext, responseContext);
            }
        }
    }

    private static final class BoundReaderInterceptor extends BoundComponent implements ReaderInterceptor {
        private final ReaderInterceptor delegate;

        BoundReaderInterceptor(ReaderInterceptor delegate, Set<Class<? extends Annotation>> bindings) {
            super(delegate, bindings);
            this.delegate = delegate;
        }

        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            return matches(context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY))
                ? delegate.aroundReadFrom(context)
                : context.proceed();
        }
    }

    private static final class BoundWriterInterceptor extends BoundComponent implements WriterInterceptor {
        private final WriterInterceptor delegate;

        BoundWriterInterceptor(WriterInterceptor delegate, Set<Class<? extends Annotation>> bindings) {
            super(delegate, bindings);
            this.delegate = delegate;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            if (matches(context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY))) {
                delegate.aroundWriteTo(context);
            } else {
                context.proceed();
            }
        }
    }
}
