package io.muserver.rest;

import io.muserver.MuHandlerBuilder;
import io.muserver.Mutils;
import io.muserver.handlers.CORSHandlerBuilder;
import io.muserver.openapi.InfoObject;
import io.muserver.openapi.OpenAPIObjectBuilder;
import io.muserver.openapi.SchemaObject;
import io.muserver.openapi.SchemaObjectBuilder;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.*;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static java.util.Arrays.asList;

/**
 * Used to create a {@link RestHandler} for handling JAX-RS REST resources.
 *
 * @see #restHandler(Object...)
 */
public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private static final Comparator<Object> INTERCEPTOR_PRIORITY = Comparator.comparingInt(RestHandlerBuilder::priority);
    private static final ExceptionMapper<Throwable> DEFAULT_EXCEPTION_MAPPER = ProblemDetailsExceptionMapperBuilder.problemDetailsExceptionMapper().build();

    private final List<Object> resources = new ArrayList<>();
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<Function<Providers, MessageBodyWriter<?>>> customWriterRegistrations = new ArrayList<>();
    private final List<WriterInterceptor> writerInterceptors = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<Function<Providers, MessageBodyReader<?>>> customReaderRegistrations = new ArrayList<>();
    private final List<ReaderInterceptor> readerInterceptors = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private final List<SchemaReference> customSchemas = new ArrayList<>();
    private @Nullable String openApiJsonUrl;
    private @Nullable String openApiHtmlUrl;
    private @Nullable OpenAPIObjectBuilder openAPIObject;
    private @Nullable String openApiHtmlCss;
    private final List<JaxRSProviders.ExceptionMapperRegistration<?>> exceptionMappers = new ArrayList<>();
    private final List<JaxRSProviders.ContextResolverRegistration<?>> contextResolvers = new ArrayList<>();
    private final List<ContainerRequestFilter> preMatchRequestFilters = new ArrayList<>();
    private final List<ContainerRequestFilter> requestFilters = new ArrayList<>();
    private final List<ContainerResponseFilter> responseFilters = new ArrayList<>();
    private CORSConfig corsConfig = CORSConfigBuilder.disabled().build();
    private final List<SchemaObjectCustomizer> schemaObjectCustomizers = new ArrayList<>();
    private @Nullable CollectionParameterStrategy collectionParameterStrategy;
    private @Nullable Application application;
    {
        exceptionMappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(
            Throwable.class, DEFAULT_EXCEPTION_MAPPER, true));
    }

    /**
     * Adds one or more rest resources to this handler
     *
     * @param resources One or more instances of classes that are decorated with {@link jakarta.ws.rs.Path} annotations.
     * @return This builder
     */
    public RestHandlerBuilder addResource(Object... resources) {
        Mutils.notNull("resources", resources);
        this.resources.addAll(asList(resources));
        return this;
    }

    /**
     * <p>Registers an object that can write custom classes to responses.</p>
     * <p>For example, if you return an instance of <code>MyClass</code> from a REST method, you need to specify how
     * that gets serialised with a <code>MessageBodyWriter&lt;MyClass&gt;</code> writer.</p>
     *
     * @param <T>    The type of object that the writer can serialise
     * @param writer A response body writer
     * @return This builder
     */
    public <T> RestHandlerBuilder addCustomWriter(MessageBodyWriter<T> writer) {
        Objects.requireNonNull(writer, "writer");
        customWriters.add(writer);
        customWriterRegistrations.add(providers -> writer);
        return this;
    }

    /**
     * Registers a factory for a custom response body writer that needs access to the providers registered for this
     * REST handler. The factory is invoked once each time {@link #build()} is called. The supplied {@link Providers}
     * instance may be retained by the writer, but it cannot be queried until the build has completed.
     *
     * @param writerFactory Creates a response body writer for a single REST handler
     * @param <T> The type of object that the writer can serialise
     * @return This builder
     */
    public <T> RestHandlerBuilder addCustomWriter(
        Function<Providers, ? extends MessageBodyWriter<T>> writerFactory) {
        Objects.requireNonNull(writerFactory, "writerFactory");
        customWriterRegistrations.add(providers -> writerFactory.apply(providers));
        return this;
    }

    /**
     * <p>Registers an object that can deserialise request bodies into custom classes.</p>
     * <p>For example, if you specify that the request body is a <code>MyClass</code>, you need to specify how
     * that gets deserialised with a <code>MessageBodyReader&lt;MyClass&gt;</code> reader.</p>
     *
     * @param <T>    The type of object that the reader can deserialise
     * @param reader A request body reader
     * @return This builder
     */
    public <T> RestHandlerBuilder addCustomReader(MessageBodyReader<T> reader) {
        Objects.requireNonNull(reader, "reader");
        customReaders.add(reader);
        customReaderRegistrations.add(providers -> reader);
        return this;
    }

    /**
     * Registers a factory for a custom request body reader that needs access to the providers registered for this
     * REST handler. The factory is invoked once each time {@link #build()} is called. The supplied {@link Providers}
     * instance may be retained by the reader, but it cannot be queried until the build has completed.
     *
     * @param readerFactory Creates a request body reader for a single REST handler
     * @param <T> The type of object that the reader can deserialise
     * @return This builder
     */
    public <T> RestHandlerBuilder addCustomReader(
        Function<Providers, ? extends MessageBodyReader<T>> readerFactory) {
        Objects.requireNonNull(readerFactory, "readerFactory");
        customReaderRegistrations.add(providers -> readerFactory.apply(providers));
        return this;
    }

    /**
     * Registers a context resolver for a given context type. More than one resolver can be registered for the same
     * type; {@link jakarta.ws.rs.Produces} annotations on the resolver classes are used to select and order matches.
     *
     * @param contextType The type of context supplied by the resolver
     * @param resolver The context resolver
     * @param <T> The context type
     * @return This builder
     */
    public <T> RestHandlerBuilder addContextResolver(Class<T> contextType, ContextResolver<T> resolver) {
        contextResolvers.add(new JaxRSProviders.ContextResolverRegistration<>(
            Objects.requireNonNull(contextType, "contextType"),
            Objects.requireNonNull(resolver, "resolver")));
        return this;
    }

    /**
     * <p>Registers an object that can convert rest method parameters (e.g. querystring, header, form or path params)
     * into custom classes.</p>
     * <p>In most cases, it is easier to instead use {@link #addCustomParamConverter(Class, ParamConverter)}</p>
     *
     * @param paramConverterProvider A provider of parameter converters
     * @return This builder
     */
    public RestHandlerBuilder addCustomParamConverterProvider(ParamConverterProvider paramConverterProvider) {
        customParamConverterProviders.add(paramConverterProvider);
        return this;
    }

    /**
     * <p>Registers a parameter converter class that convert strings to and from a custom class.</p>
     * <p>This allows you to specify query string parameters, form values, header params and path params as custom classes.</p>
     * <p>For more functionality, {@link #addCustomParamConverterProvider(ParamConverterProvider)} is also available.</p>
     *
     * @param paramClass The class that this converter is meant for.
     * @param converter  The converter
     * @param <P>        The type of the parameter
     * @return This builder
     */
    public <P> RestHandlerBuilder addCustomParamConverter(Class<P> paramClass, ParamConverter<P> converter) {
        return addCustomParamConverterProvider(new ParamConverterProvider() {
            @Override
            public <T> @Nullable ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
                if (!rawType.equals(paramClass)) {
                    return null;
                }
                return (ParamConverter<T>) converter;
            }
        });
    }

    /**
     * Enables an <a href="https://www.openapis.org">Open API</a> JSON URL at the specified endpoint. This JSON describes the API exposed
     * by the rest resources declared by this builder, and can be used by UIs such as Swagger.
     *
     * @param url The URL to serve from, for example <code>/openapi.json</code> or <code>null</code> to disable the JSON endpoint. Disabled by default.
     * @return The current Rest Handler Builder
     * @see #withOpenApiDocument(OpenAPIObjectBuilder)
     * @see #withOpenApiHtmlUrl(String)
     */
    public RestHandlerBuilder withOpenApiJsonUrl(@Nullable String url) {
        this.openApiJsonUrl = url;
        return this;
    }

    /**
     * Enables a simple HTML endpoint that documents the API exposed by the rest resources declared by this builder.
     *
     * @param url The URL to serve from, for example <code>/api.html</code> or <code>null</code> to disable the HTML endpoint. Disabled by default.
     * @return The current Rest Handler Builder
     * @see #withOpenApiDocument(OpenAPIObjectBuilder)
     * @see #withOpenApiJsonUrl(String)
     * @see #withOpenApiHtmlCss(String)
     */
    public RestHandlerBuilder withOpenApiHtmlUrl(@Nullable String url) {
        this.openApiHtmlUrl = url;
        return this;
    }

    /**
     * Specifies if values passed to method parameters with {@link jakarta.ws.rs.QueryParam} or {@link jakarta.ws.rs.HeaderParam} annotations should be transformed or not.
     * <p>The primary use of this is to allow querystring parameters such as <code>/path?value=one,two,three</code> to be interpreted
     * as a list of three values rather than a single string. This only applies to parameters that are collections.</p>
     * <p>The default is {@link CollectionParameterStrategy#NO_TRANSFORM} which is the JAX-RS standard.</p>
     * @param collectionParameterStrategy The strategy to use
     * @return This builder
     */
    public RestHandlerBuilder withCollectionParameterStrategy(CollectionParameterStrategy collectionParameterStrategy) {
        this.collectionParameterStrategy = collectionParameterStrategy;
        return this;
    }

    /**
     * When using the HTML endpoint made available by calling {@link #withOpenApiDocument(OpenAPIObjectBuilder)}
     * this allows you to override the default CSS that is used.
     *
     * @param css A string containing a style sheet definition.
     * @return The current Rest Handler Builder
     */
    public RestHandlerBuilder withOpenApiHtmlCss(@Nullable String css) {
        this.openApiHtmlCss = css;
        return this;
    }

    /**
     * <p>Use this value to create JSON and HTML documentation for your rest service.</p>
     * <p>Minimal example:</p>
     * <pre><code>
     *     OpenAPIObjectBuilder.openAPIObject()
     *             .withInfo(InfoObjectBuilder.infoObject()
     *                 .withTitle("Mu Server Sample API")
     *                 .withVersion("1.0")
     *                 .build())
     * </code></pre>
     * <p>Extended example:</p>
     * <pre><code>
     *     OpenAPIObjectBuilder.openAPIObject()
     *             .withInfo(InfoObjectBuilder.infoObject()
     *                 .withTitle("Mu Server Sample API")
     *                 .withVersion("1.0")
     *                 .withLicense(LicenseObjectBuilder.Apache2_0())
     *                 .withDescription("This is the **description**\n\nWhich is markdown")
     *                 .withTermsOfService(URI.create("http://example.org/terms/"))
     *                 .build())
     *             .withExternalDocs(externalDocumentationObject()
     *                 .withDescription("Full documentation")
     *                 .withUrl(URI.create("http://example.org/docs"))
     *                 .build())
     * </code></pre>
     * <p>The path information and operation information will be automatically generated. By default, you can access
     * the Open API specification of your rest service at <code>/openapi.json</code> or view the HTML at
     * <code>/api.html</code></p>
     *
     * @param openAPIObject An API Object builder with the {@link OpenAPIObjectBuilder#withInfo(InfoObject)} set.
     * @return The current Rest Handler Builder
     * @see OpenAPIObjectBuilder#openAPIObject()
     * @see #withOpenApiJsonUrl(String)
     * @see #withOpenApiHtmlUrl(String)
     */
    public RestHandlerBuilder withOpenApiDocument(OpenAPIObjectBuilder openAPIObject) {
        this.openAPIObject = openAPIObject;
        return this;
    }

    /**
     * <p>Adds a mapper that converts an exception to a response.</p>
     * <p>For example, you may create a custom exception such as a ValidationException that you throw from your
     * jax-rs methods. A mapper for this exception type could return a Response with a 400 code and a custom
     * validation error message.</p>
     * <p>Jakarta REST behavior is registered by default for otherwise-unmapped exceptions via
     * {@link ProblemDetailsExceptionMapper}, mapping {@link Throwable} to RFC 9457 problem-details JSON. To restore
     * Mu Server's standard HTML error fallback, remove the default mapping:</p>
     * <pre><code>
     * restHandlerBuilder.removeExceptionMapper(Throwable.class);
     * </code></pre>
     *
     * @param <T>             The exception type that the mapper can handle
     * @param exceptionClass  The type of exception to map.
     * @param exceptionMapper A function that creates a {@link jakarta.ws.rs.core.Response} suitable for the exception.
     * @return Returns this builder.
     */
    public <T extends Throwable> RestHandlerBuilder addExceptionMapper(Class<T> exceptionClass, ExceptionMapper<T> exceptionMapper) {
        if (exceptionClass == null) {
            throw new IllegalArgumentException("exceptionClass cannot be null");
        }
        if (exceptionMapper == null) {
            throw new IllegalArgumentException("exceptionMapper cannot be null. Use removeExceptionMapper(" + exceptionClass.getName() + ") to remove a mapper.");
        }
        removeExceptionMapper(exceptionClass);
        addExceptionMapper(exceptionClass, exceptionMapper, false);
        return this;
    }

    <T extends Throwable> void addApplicationExceptionMapper(Class<T> exceptionClass, ExceptionMapper<T> exceptionMapper) {
        addExceptionMapper(exceptionClass, exceptionMapper, false);
    }

    private <T extends Throwable> void addExceptionMapper(Class<T> exceptionClass, ExceptionMapper<T> exceptionMapper,
                                                           boolean isBuiltIn) {
        exceptionMappers.add(new JaxRSProviders.ExceptionMapperRegistration<>(
            exceptionClass, exceptionMapper, isBuiltIn));
    }

    /**
     * Removes a previously-registered exception mapper. If no mapper has been registered for the given exception type,
     * this is a no-op.
     *
     * @param exceptionClass The type of exception whose mapper should be removed.
     * @return This builder.
     */
    public RestHandlerBuilder removeExceptionMapper(Class<? extends Throwable> exceptionClass) {
        if (exceptionClass == null) {
            throw new IllegalArgumentException("exceptionClass cannot be null");
        }
        this.exceptionMappers.removeIf(registration -> registration.exceptionType.equals(exceptionClass));
        return this;
    }

    /**
     * Registers an object that is able to customize {@link io.muserver.openapi.SchemaObject}s generated by this rest handler
     * for OpenAPI documentation.
     * <p>This is only used when calling the URL specified by {@link #withOpenApiJsonUrl(String)}</p>
     * <p><strong>Note:</strong> if a rest resource implements {@link SchemaObjectCustomizer} then it will be automatically
     * registered.</p>
     * @param customizer The customizer to register
     * @return This builder
     */
    public RestHandlerBuilder addSchemaObjectCustomizer(SchemaObjectCustomizer customizer) {
        this.schemaObjectCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * <p>Creates a handler builder for JAX-RS REST services.</p>
     * <p>Note that CORS is disabled by default.</p>
     *
     * @param resources Instances of classes that have a {@link jakarta.ws.rs.Path} annotation.
     * @return Returns a builder that can be used to specify more config
     */
    public static RestHandlerBuilder restHandler(Object... resources) {
        return new RestHandlerBuilder().addResource(resources);
    }

    /**
     * Creates a handler builder from the singleton resources and supported server-side providers declared by a
     * JAX-RS {@link Application}.
     * <p>Mu Server supports resource instances returned by {@link Application#getSingletons()}, but not resource
     * classes returned by {@link Application#getClasses()} because those have a per-request lifecycle by default.
     * Singleton resources must therefore be safe to use concurrently. Provider classes are instantiated once using a
     * public no-argument constructor. Application properties, features, dynamic features, automatic
     * discovery, and {@link jakarta.ws.rs.ApplicationPath} mounting are not supported.</p>
     * <p>The supplied instance is available to resource methods using {@link jakarta.ws.rs.core.Context}
     * {@code Application} parameters.</p>
     *
     * @param application The application configuration to adapt.
     * @return A builder containing the application's supported singleton components.
     */
    public static RestHandlerBuilder fromApplication(Application application) {
        return ApplicationRegistrar.from(application);
    }

    void setApplication(Application application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    /**
     * <p>Specifies the CORS config for the REST services. Defaults to {@link CORSConfigBuilder#disabled()}</p>
     * <p>Note: an alternative to adding CORS config to the Rest Handler Builder is to add a handler with
     * {@link CORSHandlerBuilder#corsHandler()} which can apply the headers to all handlers (not just JAX-RS endpoints).</p>
     *
     * @param corsConfig The CORS config to use
     * @return This builder.
     * @see CORSConfigBuilder
     */
    public RestHandlerBuilder withCORS(CORSConfig corsConfig) {
        this.corsConfig = corsConfig;
        return this;
    }

    /**
     * <p>Specifies the CORS config for the REST services. Defaults to {@link CORSConfigBuilder#disabled()}</p>
     * <p>Note: an alternative to adding CORS config to the Rest Handler Builder is to add a handler with
     * {@link CORSHandlerBuilder#corsHandler()} which can apply the headers to all handlers (not just JAX-RS endpoints).</p>
     *
     * @param corsConfig The CORS config to use
     * @return This builder.
     * @see CORSConfigBuilder
     */
    public RestHandlerBuilder withCORS(CORSConfigBuilder corsConfig) {
        return withCORS(corsConfig.build());
    }

    /**
     * <p>Registers a request filter, which is run before a rest method is executed.</p>
     * <p>It will be run after the method has been matched, or if the {@link PreMatching} annotation is applied to the
     * filter then it will run before matching occurs.</p>
     * <p>To access the {@link jakarta.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     *
     * @param filter The filter to register
     * @return This builder
     */
    public RestHandlerBuilder addRequestFilter(ContainerRequestFilter filter) {
        if (filter.getClass().getDeclaredAnnotation(PreMatching.class) != null) {
            this.preMatchRequestFilters.add(filter);
        } else {
            this.requestFilters.add(filter);
        }
        return this;
    }

    /**
     * Registers a response filter, which is called after execution of a method takes place.
     * <p>To access the {@link jakarta.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     *
     * @param filter The filter to register
     * @return This builder
     */
    public RestHandlerBuilder addResponseFilter(ContainerResponseFilter filter) {
        this.responseFilters.add(filter);
        return this;
    }

    /**
     * Registers a custom OpenAPI schema description for the given class.
     * <p>This allows you to provide rich schema objects (created with {@link SchemaObjectBuilder#schemaObject()}) in your
     * OpenAPI documents. Wherever the give type is used as a parameter or body, the given schema will be used to describe it.</p>
     * <p><strong>Warning:</strong> When generating OpenAPI documentation, the schema information will be added to the <code>/components/schemas</code>
     * section with a key equal to the simple class name of the given data class. If you do not wish to expose the class name
     * in your API documentation, you can override it by annotating the class with a {@link Description} annotation in which
     * case the <code>value</code> field will be used.</p>
     * @param dataClass The type of class to describe
     * @param schema The schema object describing the class
     * @return This builder
     */
    public RestHandlerBuilder addCustomSchema(Class<?> dataClass, SchemaObject schema) {
        String id;
        Description desc = dataClass.getDeclaredAnnotation(Description.class);
        if (desc != null) {
            id = desc.value();
        } else {
            id = dataClass.getSimpleName();
        }
        while (true) {
            boolean anyMatch = false;
            for (SchemaReference customSchema : customSchemas) {
                if (customSchema.id.equals(id)) {
                    anyMatch = true;
                    break;
                }
            }
            if (anyMatch) {
                id += "0";
            } else {
                break;
            }
        }
        String regex = "^[a-zA-Z0-9.\\-_]+$";
        if (!id.matches(regex)) {
            throw new IllegalArgumentException("The ID " + id + " given for custom schema for class " + dataClass.getName() + " does not match required regex " + regex);
        }
        this.customSchemas.add(new SchemaReference(id, dataClass, null, schema));
        return this;
    }

    /**
     * Registers a writer interceptor allowing for inspection and alteration of response bodies.
     * <p>Interceptors are executed in ascending {@link Priority} order, defaulting to {@link Priorities#USER}, and are
     * called before any message body writers added by {@link #addCustomWriter(MessageBodyWriter)}.</p>
     * <p>To access the {@link jakarta.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     * @param writerInterceptor The interceptor to add. If <code>null</code> then this is a no-op.
     * @return This builder
     */
    public RestHandlerBuilder addWriterInterceptor(@Nullable WriterInterceptor writerInterceptor) {
        if (writerInterceptor != null) {
            this.writerInterceptors.add(writerInterceptor);
            this.writerInterceptors.sort(INTERCEPTOR_PRIORITY);
        }
        return this;
    }

    /**
     * Registers a reader interceptor allowing for inspection and alteration of request bodies.
     * <p>Interceptors are executed in ascending {@link Priority} order, defaulting to {@link Priorities#USER}, and are
     * called before any message body readers added by {@link #addCustomReader(MessageBodyReader)}.</p>
     * <p>To access the {@link jakarta.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     * @param readerInterceptor The interceptor to add. If <code>null</code> then this is a no-op.
     * @return This builder
     */
    public RestHandlerBuilder addReaderInterceptor(@Nullable ReaderInterceptor readerInterceptor) {
        if (readerInterceptor != null) {
            this.readerInterceptors.add(0, readerInterceptor);
            this.readerInterceptors.sort(INTERCEPTOR_PRIORITY);
        }
        return this;
    }

    private static int priority(Object interceptor) {
        return PrioritizedComponent.priorityOf(interceptor);
    }

    /**
     * @return The current value of this property
     */
    public List<Object> resources() {
        return Collections.unmodifiableList(resources);
    }

    /**
     * @return The current value of this property
     */
    public List<MessageBodyWriter<?>> customWriters() {
        return customWriters.stream().map(w -> (MessageBodyWriter<?>)w).collect(Collectors.toList());
    }

    /**
     * @return The current value of this property
     */
    public List<WriterInterceptor> writerInterceptors() {
        return Collections.unmodifiableList(writerInterceptors);
    }

    /**
     * @return The current value of this property
     */
    public List<MessageBodyReader<?>> customReaders() {
        return customReaders.stream().map(r -> (MessageBodyReader<?>)r).collect(Collectors.toList());
    }

    /**
     * @return The current value of this property
     */
    public List<ReaderInterceptor> readerInterceptors() {
        return Collections.unmodifiableList(readerInterceptors);
    }

    /**
     * @return The current value of this property
     */
    public List<ParamConverterProvider> customParamConverterProviders() {
        return Collections.unmodifiableList(customParamConverterProviders);
    }

    /**
     * @return The current value of this property
     */
    public Map<Class<?>, SchemaObject> customSchemas() {
        return customSchemas.stream().collect(Collectors.toMap(ref -> ref.type, ref -> ref.schema));
    }

    /**
     * @return The current value of this property
     */
    public @Nullable String openApiJsonUrl() {
        return openApiJsonUrl;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable String openApiHtmlUrl() {
        return openApiHtmlUrl;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable OpenAPIObjectBuilder openAPIObject() {
        return openAPIObject;
    }

    /**
     * @return The current value of this property
     */
    public @Nullable String openApiHtmlCss() {
        return openApiHtmlCss;
    }

    /**
     * @return The current value of this property
     */
    public Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers() {
        Map<Class<? extends Throwable>, JaxRSProviders.ExceptionMapperRegistration<?>> effective = new LinkedHashMap<>();
        for (JaxRSProviders.ExceptionMapperRegistration<?> registration : exceptionMappers) {
            JaxRSProviders.ExceptionMapperRegistration<?> current = effective.get(registration.exceptionType);
            if (registration.preferredTo(current)) {
                effective.put(registration.exceptionType, registration);
            }
        }
        Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> result = new LinkedHashMap<>();
        effective.forEach((type, registration) -> result.put(type, registration.mapper));
        return Collections.unmodifiableMap(result);
    }

    /**
     * @return The current value of this property
     */
    public List<ContainerRequestFilter> preMatchRequestFilters() {
        return Collections.unmodifiableList(preMatchRequestFilters);
    }

    /**
     * @return The current value of this property
     */
    public List<ContainerRequestFilter> requestFilters() {
        return Collections.unmodifiableList(requestFilters);
    }

    /**
     * @return The current value of this property
     */
    public List<ContainerResponseFilter> responseFilters() {
        return Collections.unmodifiableList(responseFilters);
    }

    /**
     * @return The current value of this property
     */
    public CORSConfig corsConfig() {
        return corsConfig;
    }

    /**
     * @return The current value of this property
     */
    public List<SchemaObjectCustomizer> schemaObjectCustomizers() {
        return Collections.unmodifiableList(schemaObjectCustomizers);
    }

    /**
     * @return The current value of this property
     */
    public CollectionParameterStrategy collectionParameterStrategy() {
        return collectionParameterStrategy == null ? CollectionParameterStrategy.NO_TRANSFORM : collectionParameterStrategy;
    }

    /**
     * @return The newly build {@link RestHandler}
     */
    @Override
    public RestHandler build() {
        JaxRSProviders providers = new JaxRSProviders();
        List<MessageBodyReader> readers = new ArrayList<>();
        for (Function<Providers, MessageBodyReader<?>> registration : customReaderRegistrations) {
            MessageBodyReader<?> reader = registration.apply(providers);
            if (reader == null) {
                throw new IllegalStateException("A custom message body reader factory returned null");
            }
            readers.add(reader);
        }
        List<MessageBodyWriter> writers = new ArrayList<>();
        for (Function<Providers, MessageBodyWriter<?>> registration : customWriterRegistrations) {
            MessageBodyWriter<?> writer = registration.apply(providers);
            if (writer == null) {
                throw new IllegalStateException("A custom message body writer factory returned null");
            }
            writers.add(writer);
        }
        EntityProviders entityProviders = new EntityProviders(
            EntityProviders.builtInReaders(), readers, EntityProviders.builtInWriters(), writers);
        List<ParamConverterProvider> paramConverterProviders = new ArrayList<>(customParamConverterProviders);
        paramConverterProviders.add(new BuiltInParamConverterProvider());

        List<ResourceClass> list = new ArrayList<>();
        SchemaObjectCustomizer schemaObjectCustomizer = new CompositeSchemaObjectCustomizer(schemaObjectCustomizers);
        for (Object resource : resources) {
            if (resource instanceof SchemaObjectCustomizer && !schemaObjectCustomizers.contains(resource)) {
                schemaObjectCustomizers.add((SchemaObjectCustomizer) resource);
            }
        }
        for (Object restResource : resources) {
            list.add(ResourceClass.fromObject(restResource, paramConverterProviders, schemaObjectCustomizer));
        }
        List<ResourceClass> roots = Collections.unmodifiableList(list);

        @Nullable OpenApiDocumentor documentor = null;
        if (openApiHtmlUrl != null || openApiJsonUrl != null) {
            if (openApiHtmlCss == null) {
                InputStream cssStream = Objects.requireNonNull(
                    RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css"),
                    "Bundled OpenAPI CSS was not found");
                Scanner scanner = new Scanner(cssStream, StandardCharsets.UTF_8).useDelimiter("\\A");
                openApiHtmlCss = scanner.next();
                scanner.close();

            }
            OpenAPIObjectBuilder openAPIObjectToUse = this.openAPIObject == null ? OpenAPIObjectBuilder.openAPIObject() : this.openAPIObject;
            openAPIObjectToUse.withPaths(pathsObject().build());
            documentor = new OpenApiDocumentor(roots, openApiJsonUrl, openApiHtmlUrl, openAPIObjectToUse.build(), openApiHtmlCss, corsConfig, new ArrayList<>(customSchemas), schemaObjectCustomizer, paramConverterProviders);
        }

        CustomExceptionMapper customExceptionMapper = new CustomExceptionMapper(providers);

        FilterManagerThing filterManagerThing = new FilterManagerThing(preMatchRequestFilters, requestFilters, responseFilters);

        CollectionParameterStrategy cps = this.collectionParameterStrategy;
        if (cps == null) {
            cps = CollectionParameterStrategy.NO_TRANSFORM;
        }

        Application application = this.application == null ? applicationSnapshot(readers, writers) : this.application;
        RestHandler handler = new RestHandler(application, providers, roots, documentor, customExceptionMapper, filterManagerThing,
            corsConfig, paramConverterProviders, schemaObjectCustomizer, readerInterceptors, writerInterceptors, cps);
        providers.initialize(entityProviders, exceptionMappers, contextResolvers);
        return handler;
    }

    private Application applicationSnapshot(List<MessageBodyReader> readers, List<MessageBodyWriter> writers) {
        Set<Object> singletons = Collections.newSetFromMap(new IdentityHashMap<>());
        singletons.addAll(resources);
        singletons.addAll(readers);
        singletons.addAll(writers);
        singletons.addAll(customParamConverterProviders);
        for (JaxRSProviders.ExceptionMapperRegistration<?> registration : exceptionMappers) {
            if (!registration.isBuiltIn) {
                singletons.add(registration.mapper);
            }
        }
        for (JaxRSProviders.ContextResolverRegistration<?> registration : contextResolvers) {
            singletons.add(registration.resolver);
        }
        singletons.addAll(preMatchRequestFilters);
        singletons.addAll(requestFilters);
        singletons.addAll(responseFilters);
        singletons.addAll(readerInterceptors);
        singletons.addAll(writerInterceptors);
        return new RuntimeApplication(singletons);
    }

    private static final class RuntimeApplication extends Application {
        private final Set<Object> singletons;

        private RuntimeApplication(Set<Object> singletons) {
            this.singletons = Collections.unmodifiableSet(singletons);
        }

        @Override
        public Set<Class<?>> getClasses() {
            return Collections.emptySet();
        }

        @Override
        public Set<Object> getSingletons() {
            return singletons;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Collections.emptyMap();
        }
    }
}
