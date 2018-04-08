package io.muserver.rest;

import io.muserver.MuHandlerBuilder;
import io.muserver.openapi.InfoObject;
import io.muserver.openapi.OpenAPIObjectBuilder;

import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

import static io.muserver.openapi.PathsObjectBuilder.pathsObject;

public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private Object[] resources;
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private String openApiJsonUrl = "/openapi.json";
    private String openApiHtmlUrl = "/api.html";
    private OpenAPIObjectBuilder openAPIObject;
    private String openApiHtmlCss = null;

    public RestHandlerBuilder(Object... resources) {
        this.resources = resources;
    }

    public RestHandlerBuilder addResource(Object... resources) {
        this.resources = Stream.of(this.resources, resources).flatMap(Stream::of).toArray(Object[]::new);
        return this;
    }

    public RestHandlerBuilder addCustomWriter(MessageBodyWriter writer) {
        customWriters.add(writer);
        return this;
    }
    public RestHandlerBuilder addCustomReader(MessageBodyReader reader) {
        customReaders.add(reader);
        return this;
    }
    public RestHandlerBuilder addCustomParamConverterProvider(ParamConverterProvider paramConverterProvider) {
        customParamConverterProviders.add(paramConverterProvider);
        return this;
    }

    /**
     * If {@link #withOpenApiDocument(OpenAPIObjectBuilder)} is set, this specifies the OPEN API JSON URL
     * (relative to any contexts).
     * @param url The URL to serve from, or <code>null</code> to disable the JSON endpoint. By default it is <code>/openapi.json</code>
     * @return The current Rest Handler Builder
     */
    public RestHandlerBuilder withOpenApiJsonUrl(String url) {
        this.openApiJsonUrl = url;
        return this;
    }

    /**
     * If {@link #withOpenApiDocument(OpenAPIObjectBuilder)} is set, this specifies the HTML API documentation URL
     * (relative to any contexts).
     * @param url The URL to serve from, or <code>null</code> to disable the HTML endpoint. By default it is <code>/api.html</code>
     * @return The current Rest Handler Builder
     */
    public RestHandlerBuilder withOpenApiHtmlUrl(String url) {
        this.openApiHtmlUrl = url;
        return this;
    }

    /**
     * When using the HTML endpoint made available by calling {@link #withOpenApiDocument(OpenAPIObjectBuilder)}
     * this allows you to override the default CSS that is used.
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
     * @see OpenAPIObjectBuilder#openAPIObject()
     * @see #withOpenApiJsonUrl(String)
     * @see #withOpenApiHtmlUrl(String)
     * @param openAPIObject An API Object builder with the {@link OpenAPIObjectBuilder#withInfo(InfoObject)} set.
     * @return The current Rest Handler Builder
     */
    public RestHandlerBuilder withOpenApiDocument(OpenAPIObjectBuilder openAPIObject) {
        this.openAPIObject = openAPIObject;
        return this;
    }

    /**
     * <p>Enables documentation generation. This is a shorthand for <code>withOpenApiDocument(OpenAPIObjectBuilder.openAPIObject())</code></p>
     * <p>For more advanced options, use {@link #withOpenApiDocument(OpenAPIObjectBuilder)} directly.</p>
     * @return The current Rest Handler Builder
     */
    public RestHandlerBuilder withDocumentation() {
        return this.withOpenApiDocument(OpenAPIObjectBuilder.openAPIObject());
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
        if (openAPIObject != null) {
            if (openApiHtmlCss == null) {
                InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
                openApiHtmlCss = new Scanner(cssStream, "UTF-8").useDelimiter("\\A").next();
            }
            openAPIObject.withPaths(pathsObject().build());
            documentor = new OpenApiDocumentor(roots, openApiJsonUrl, openApiHtmlUrl, openAPIObject.build(), openApiHtmlCss);
        }
        return new RestHandler(entityProviders, roots, documentor);
    }

    public static RestHandlerBuilder restHandler(Object... resources) {
        return new RestHandlerBuilder(resources);
    }

    /**
     * @deprecated Use restHandler(resources).build() instead.
     * @param resources Resources to register
     * @return Returns a rest handler with the given resources.
     */
    @Deprecated
    public static RestHandler create(Object... resources) {
        return restHandler(resources).build();
    }
}
