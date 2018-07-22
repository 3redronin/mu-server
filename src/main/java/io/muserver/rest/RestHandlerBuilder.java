package io.muserver.rest;

import io.muserver.MuHandlerBuilder;
import io.muserver.openapi.InfoObject;
import io.muserver.openapi.OpenAPIObjectBuilder;

import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.ext.*;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Stream;

import static io.muserver.openapi.PathsObjectBuilder.pathsObject;

/**
 * Used to create a {@link RestHandler} for handling JAX-RS REST resources.
 * @see #restHandler(Object...)
 */
public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private Object[] resources;
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private String openApiJsonUrl = null;
    private String openApiHtmlUrl = null;
    private OpenAPIObjectBuilder openAPIObject;
    private String openApiHtmlCss = null;
    private Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers = new HashMap<>();
    private List<ContainerRequestFilter> preMatchRequestFilters = new ArrayList<>();
    private List<ContainerRequestFilter> requestFilters = new ArrayList<>();
    private List<ContainerResponseFilter> responseFilters = new ArrayList<>();
    private CORSConfig corsConfig = CORSConfigBuilder.disabled().build();

    public RestHandlerBuilder(Object... resources) {
        this.resources = resources;
    }

    /**
     * Adds one or more rest resources to this handler
     * @param resources One or more instances of classes that are decorated with {@link javax.ws.rs.Path} annotations.
     * @return This builder
     */
    public RestHandlerBuilder addResource(Object... resources) {
        this.resources = Stream.of(this.resources, resources).flatMap(Stream::of).toArray(Object[]::new);
        return this;
    }

    /**
     * <p>Registers an object that can write custom classes to responses.</p>
     * <p>For example, if you return an instance of <code>MyClass</code> from a REST method, you need to specify how
     * that gets serialised with a <code>MessageBodyWriter&lt;MyClass&gt;</code> writer.</p>
     * @param <T> The type of object that the writer can serialise
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
     * @param <T> The type of object that the reader can deserialise
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
     * @param paramClass The class that this converter is meant for.
     * @param converter The converter
     * @param <P> The type of the parameter
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
     * @param <T> The exception type that the mapper can handle
     * @param exceptionClass The type of exception to map.
     * @param exceptionMapper A function that creates a {@link javax.ws.rs.core.Response} suitable for the exception.
     * @return Returns this builder.
     */
    public <T extends Throwable> RestHandlerBuilder addExceptionMapper(Class<T> exceptionClass, ExceptionMapper<T> exceptionMapper) {
        this.exceptionMappers.put(exceptionClass, exceptionMapper);
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

    public RestHandler build() {
        List<MessageBodyReader> readers = EntityProviders.builtInReaders();
        readers.addAll(customReaders);
        List<MessageBodyWriter> writers = EntityProviders.builtInWriters();
        writers.addAll(customWriters);
        EntityProviders entityProviders = new EntityProviders(readers, writers);
        List<ParamConverterProvider> paramConverterProviders = new ArrayList<>(customParamConverterProviders);
        paramConverterProviders.add(new BuiltInParamConverterProvider());

        HashSet<ResourceClass> set = new HashSet<>();
        for (Object restResource : resources) {
            set.add(ResourceClass.fromObject(restResource, paramConverterProviders));
        }
        Set<ResourceClass> roots = Collections.unmodifiableSet(set);

        OpenApiDocumentor documentor = null;
        if (openApiHtmlUrl != null || openApiJsonUrl != null) {
            if (openApiHtmlCss == null) {
                InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
                openApiHtmlCss = new Scanner(cssStream, "UTF-8").useDelimiter("\\A").next();
            }
            OpenAPIObjectBuilder openAPIObjectToUse = this.openAPIObject == null ? OpenAPIObjectBuilder.openAPIObject() : this.openAPIObject;
            openAPIObjectToUse.withPaths(pathsObject().build());
            documentor = new OpenApiDocumentor(roots, openApiJsonUrl, openApiHtmlUrl, openAPIObjectToUse.build(), openApiHtmlCss, corsConfig);
        }

        CustomExceptionMapper customExceptionMapper = new CustomExceptionMapper(exceptionMappers);

        FilterManagerThing filterManagerThing = new FilterManagerThing(preMatchRequestFilters, requestFilters, responseFilters);

        return new RestHandler(entityProviders, roots, documentor, customExceptionMapper, filterManagerThing, corsConfig);
    }

    /**
     * <p>Creates a handler builder for JAX-RS REST services.</p>
     * <p>Note that CORS is disabled by default.</p>
     * @param resources Instances of classes that have a {@link javax.ws.rs.Path} annotation.
     * @return Returns a builder that can be used to specify more config
     */
    public static RestHandlerBuilder restHandler(Object... resources) {
        return new RestHandlerBuilder(resources);
    }

    /**
     * <p>Specifies the CORS config for the REST services. Defaults to {@link CORSConfigBuilder#disabled()}</p>
     * @see CORSConfigBuilder
     * @param corsConfig The CORS config to use
     * @return This builder.
     */
    public RestHandlerBuilder withCORS(CORSConfig corsConfig) {
        this.corsConfig = corsConfig;
        return this;
    }
    /**
     * <p>Specifies the CORS config for the REST services. Defaults to {@link CORSConfigBuilder#disabled()}</p>
     * @see CORSConfigBuilder
     * @param corsConfig The CORS config to use
     * @return This builder.
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
     * @param filter The filter to register
     * @return This builder
     */
    public RestHandlerBuilder addResponseFilter(ContainerResponseFilter filter) {
        this.responseFilters.add(filter);
        return this;
    }
}
