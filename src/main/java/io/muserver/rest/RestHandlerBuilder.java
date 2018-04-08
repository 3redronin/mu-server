package io.muserver.rest;

import io.muserver.MuHandlerBuilder;
import io.muserver.openapi.OpenAPIObject;

import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Stream;

public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private Object[] resources;
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private String openApiJsonUrl = "/openapi.json";
    private OpenAPIObject openAPIObject;
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
    public RestHandlerBuilder withOpenApiJsonUrl(String url) {
        this.openApiJsonUrl = url;
        return this;
    }

    public RestHandlerBuilder withOpenApiDocument(OpenAPIObject openAPIObject) {
        this.openAPIObject = openAPIObject;
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

        InputStream cssStream = RestHandlerBuilder.class.getResourceAsStream("/io/muserver/resources/api.css");
        if (openApiHtmlCss == null) {
            openApiHtmlCss = new Scanner(cssStream, "UTF-8").useDelimiter("\\A").next();
        }

        OpenApiDocumentor documentor = new OpenApiDocumentor(roots, entityProviders, openApiJsonUrl, openAPIObject, openApiHtmlCss);
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
