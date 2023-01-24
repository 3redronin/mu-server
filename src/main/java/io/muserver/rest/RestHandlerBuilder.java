package io.muserver.rest;

import io.muserver.MuHandlerBuilder;
import io.muserver.Mutils;
import io.muserver.handlers.CORSHandlerBuilder;
import io.muserver.openapi.InfoObject;
import io.muserver.openapi.OpenAPIObjectBuilder;
import io.muserver.openapi.SchemaObject;
import io.muserver.openapi.SchemaObjectBuilder;

import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.ext.*;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static java.util.Arrays.asList;

/**
 * Used to create a {@link RestHandler} for handling JAX-RS REST resources.
 *
 * @see #restHandler(Object...)
 */
public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private final List<Object> resources = new ArrayList<>();
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<WriterInterceptor> writerInterceptors = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<ReaderInterceptor> readerInterceptors = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private final List<SchemaReference> customSchemas = new ArrayList<>();
    private String openApiJsonUrl = null;
    private String openApiHtmlUrl = null;
    private OpenAPIObjectBuilder openAPIObject;
    private String openApiHtmlCss = null;
    private final Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers = new HashMap<>();
    private final List<ContainerRequestFilter> preMatchRequestFilters = new ArrayList<>();
    private final List<ContainerRequestFilter> requestFilters = new ArrayList<>();
    private final List<ContainerResponseFilter> responseFilters = new ArrayList<>();
    private CORSConfig corsConfig = CORSConfigBuilder.disabled().build();
    private final List<SchemaObjectCustomizer> schemaObjectCustomizers = new ArrayList<>();
    private CollectionParameterStrategy collectionParameterStrategy;

    /**
     * @deprecated Use {@link #restHandler(Object...)} instead
     * @param resources The resources to use
     */
    @Deprecated
    public RestHandlerBuilder(Object... resources) {
        addResource(resources);
    }

    /**
     * Adds one or more rest resources to this handler
     *
     * @param resources One or more instances of classes that are decorated with {@link javax.ws.rs.Path} annotations.
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
        customWriters.add(writer);
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
        customReaders.add(reader);
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
            public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
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
    public RestHandlerBuilder withOpenApiJsonUrl(String url) {
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
    public RestHandlerBuilder withOpenApiHtmlUrl(String url) {
        this.openApiHtmlUrl = url;
        return this;
    }

    /**
     * Specifies if values passed to method parameters with {@link javax.ws.rs.QueryParam} or {@link javax.ws.rs.HeaderParam} annotations should be transformed or not.
     * <p>The primary use of this is to allow querystring parameters such as <code>/path?value=one,two,three</code> to be interpreted
     * as a list of three values rather than a single string. This only applies to parameters that are collections.</p>
     * <p>The default is {@link CollectionParameterStrategy#NO_TRANSFORM} which is the JAX-RS standard.</p>
     * <p><strong>Note:</strong> until MuServer 1.0, if no value is specified but methods with collection parameters are detected
     * then the handler will fail to start and this value will need to be explicitly set. This is in order to highlight the change
     * in behaviour introduced in Mu Server 0.70 where it used {@link CollectionParameterStrategy#SPLIT_ON_COMMA} behaviour.</p>
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
    public RestHandlerBuilder withOpenApiHtmlCss(String css) {
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
     *
     * @param <T>             The exception type that the mapper can handle
     * @param exceptionClass  The type of exception to map.
     * @param exceptionMapper A function that creates a {@link javax.ws.rs.core.Response} suitable for the exception.
     * @return Returns this builder.
     */
    public <T extends Throwable> RestHandlerBuilder addExceptionMapper(Class<T> exceptionClass, ExceptionMapper<T> exceptionMapper) {
        this.exceptionMappers.put(exceptionClass, exceptionMapper);
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
     * @return The current Rest Handler Builder
     * @deprecated This does nothing. To expose API endpoints, use {@link #withOpenApiJsonUrl(String)} and/or {@link #withOpenApiHtmlUrl(String)}
     */
    @Deprecated
    public RestHandlerBuilder withDocumentation() {
        return this;
    }

    /**
     * <p>Creates a handler builder for JAX-RS REST services.</p>
     * <p>Note that CORS is disabled by default.</p>
     *
     * @param resources Instances of classes that have a {@link javax.ws.rs.Path} annotation.
     * @return Returns a builder that can be used to specify more config
     */
    public static RestHandlerBuilder restHandler(Object... resources) {
        return new RestHandlerBuilder(resources);
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
     * @param resources Resources to register
     * @return Returns a rest handler with the given resources.
     * @deprecated Use restHandler(resources).build() instead.
     */
    @Deprecated
    public static RestHandler create(Object... resources) {
        return restHandler(resources).build();
    }

    /**
     * <p>Registers a request filter, which is run before a rest method is executed.</p>
     * <p>It will be run after the method has been matched, or if the {@link PreMatching} annotation is applied to the
     * filter then it will run before matching occurs.</p>
     * <p>To access the {@link javax.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
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
     * <p>To access the {@link javax.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
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
     * <p>Interceptors are executed in the order added, and are called before any message body
     * writers added by {@link #addCustomWriter(MessageBodyWriter)}.</p>
     * <p>To access the {@link javax.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     * @param writerInterceptor The interceptor to add. If <code>null</code> then this is a no-op.
     * @return This builder
     */
    public RestHandlerBuilder addWriterInterceptor(WriterInterceptor writerInterceptor) {
        if (writerInterceptor != null) {
            this.writerInterceptors.add(writerInterceptor);
        }
        return this;
    }

    /**
     * Registers a reader interceptor allowing for inspection and alteration of request bodies.
     * <p>Interceptors are executed in the order added, and are called before any message body
     * readers added by {@link #addCustomReader(MessageBodyReader)}.</p>
     * <p>To access the {@link javax.ws.rs.container.ResourceInfo} or {@link io.muserver.MuRequest} for the current
     * request, the following code can be used:</p>
     * <pre><code>
     * ResourceInfo resourceInfo = (ResourceInfo) context.getProperty(MuRuntimeDelegate.RESOURCE_INFO_PROPERTY);
     * MuRequest muRequest = (MuRequest) context.getProperty(MuRuntimeDelegate.MU_REQUEST_PROPERTY);</code></pre>
     * @param readerInterceptor The interceptor to add. If <code>null</code> then this is a no-op.
     * @return This builder
     */
    public RestHandlerBuilder addReaderInterceptor(ReaderInterceptor readerInterceptor) {
        if (readerInterceptor != null) {
            this.readerInterceptors.add(0, readerInterceptor);
        }
        return this;
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
    public String openApiJsonUrl() {
        return openApiJsonUrl;
    }

    /**
     * @return The current value of this property
     */
    public String openApiHtmlUrl() {
        return openApiHtmlUrl;
    }

    /**
     * @return The current value of this property
     */
    public OpenAPIObjectBuilder openAPIObject() {
        return openAPIObject;
    }

    /**
     * @return The current value of this property
     */
    public String openApiHtmlCss() {
        return openApiHtmlCss;
    }

    /**
     * @return The current value of this property
     */
    public Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers() {
        return Collections.unmodifiableMap(exceptionMappers);
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
        return collectionParameterStrategy;
    }

    /**
     * @return The newly build {@link RestHandler}
     */
    public RestHandler build() {
        List<MessageBodyReader> readers = EntityProviders.builtInReaders();
        readers.addAll(customReaders);
        List<MessageBodyWriter> writers = EntityProviders.builtInWriters();
        writers.addAll(customWriters);
        EntityProviders entityProviders = new EntityProviders(readers, writers);
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

        OpenApiDocumentor documentor = null;
        if (openApiHtmlUrl != null || openApiJsonUrl != null) {
            if (openApiHtmlCss == null) {
                InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
                Scanner scanner = new Scanner(cssStream, "UTF-8").useDelimiter("\\A");
                openApiHtmlCss = scanner.next();
                scanner.close();

            }
            OpenAPIObjectBuilder openAPIObjectToUse = this.openAPIObject == null ? OpenAPIObjectBuilder.openAPIObject() : this.openAPIObject;
            openAPIObjectToUse.withPaths(pathsObject().build());
            documentor = new OpenApiDocumentor(roots, openApiJsonUrl, openApiHtmlUrl, openAPIObjectToUse.build(), openApiHtmlCss, corsConfig, new ArrayList<>(customSchemas), schemaObjectCustomizer, paramConverterProviders);
        }

        CustomExceptionMapper customExceptionMapper = new CustomExceptionMapper(exceptionMappers);

        FilterManagerThing filterManagerThing = new FilterManagerThing(preMatchRequestFilters, requestFilters, responseFilters);

        CollectionParameterStrategy cps = this.collectionParameterStrategy;
        if (cps == null) {
            for (ResourceClass root : roots) {
                for (ResourceMethod rm : root.resourceMethods) {
                    for (ResourceMethodParam param : rm.params) {
                        if (Collection.class.isAssignableFrom(param.parameterHandle.getType()) && (param.source == ResourceMethodParam.ValueSource.HEADER_PARAM || param.source == ResourceMethodParam.ValueSource.QUERY_PARAM)) {
                            throw new IllegalStateException("Please specify a string handling strategy for collections for querystring and header parameters. " +
                                "Please note that the behaviour of these parameters have changed since Mu Server 0.70.0 to follow the JAX-RS standard. " +
                                "Previously, a parameter values such as 'one,two,three' when passed to a collection parameter would be interpreted as 3 values, " +
                                "however the JAX-RS standard is for this to be a single value. To follow the standard, please use " +
                                "RestHandlerBuilder.withCollectionParameterStrategy(CollectionParameterStrategy.NO_TRANSFORM) or keep early behaviour where the value is split " +
                                "into multiple values, use RestHandlerBuilder.withCollectionParameterStrategy(CollectionParameterStrategy.SPLIT_ON_COMMA) no your rest handler builder instance.");
                        }
                    }
                }
            }

            cps = CollectionParameterStrategy.NO_TRANSFORM;
        }

        return new RestHandler(entityProviders, roots, documentor, customExceptionMapper, filterManagerThing, corsConfig, paramConverterProviders, schemaObjectCustomizer, readerInterceptors, writerInterceptors, cps);
    }
}

