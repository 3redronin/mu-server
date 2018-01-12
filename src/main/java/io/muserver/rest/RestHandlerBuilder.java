package io.muserver.rest;

import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.util.ArrayList;
import java.util.List;

public class RestHandlerBuilder {

    private Object[] resources;
    private final List<MessageBodyWriter> customWriters = new ArrayList<>();
    private final List<MessageBodyReader> customReaders = new ArrayList<>();

    public RestHandlerBuilder(Object... resources) {
        this.resources = resources;
    }

    public RestHandlerBuilder withResoures(Object... resources) {
        this.resources = resources;
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


    public RestHandler build() {
        List<MessageBodyReader> readers = EntityProviders.builtInReaders();
        readers.addAll(customReaders);
        List<MessageBodyWriter> writers = EntityProviders.builtInWriters();
        writers.addAll(customWriters);
        EntityProviders entityProviders = new EntityProviders(readers, writers);
        return new RestHandler(entityProviders, resources);
    }

    public static RestHandlerBuilder restHandler(Object... resources) {
        return new RestHandlerBuilder(resources);
    }

    public static RestHandler create(Object... resources) {
        return restHandler(resources).build();
    }
}
