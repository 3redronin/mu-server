package io.muserver.rest;

import io.muserver.MuHandlerBuilder;

import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.util.*;
import java.util.stream.Stream;

public class RestHandlerBuilder implements MuHandlerBuilder<RestHandler> {

    private Object[] resources;
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();
    private final List<ParamConverterProvider> customParamConverterProviders = new ArrayList<>();
    private String openApiJsonUrl = "/openapi.json";

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
        OpenApiDocumentor documentor = new OpenApiDocumentor(roots, entityProviders, openApiJsonUrl);
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
