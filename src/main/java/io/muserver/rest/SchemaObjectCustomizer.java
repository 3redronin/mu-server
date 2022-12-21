package io.muserver.rest;

import io.muserver.MuException;
import io.muserver.openapi.SchemaObjectBuilder;

import java.util.Collections;
import java.util.List;

/**
 * A class that can customize the {@link io.muserver.openapi.SchemaObject}s generated in OpenAPI documents for JAX-RS resources.
 * <p>To register a customizer, use {@link RestHandlerBuilder#addSchemaObjectCustomizer(SchemaObjectCustomizer)} or implement
 * {@link SchemaObjectCustomizer} in any rest resources registered with a rest handler.</p>
 */
public interface SchemaObjectCustomizer {

    /**
     * Optionally change, remove, or create a new schema builder for a piece of an OpenAPI document.
     * @param builder A builder for a schema that values that have been inferred by MuServer, or by previous customizers
     * @param context Information about the part of the document that is being documented (e.g. if it is a schema object
     *                for a request body, or response body, etc) along with other data that may be useful when generating
     *                the schema object.
     * @return The schema builder
     */
    SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context);

}

class CompositeSchemaObjectCustomizer implements SchemaObjectCustomizer {

    private final List<SchemaObjectCustomizer> customizers;

    CompositeSchemaObjectCustomizer(List<SchemaObjectCustomizer> customizers) {
        this.customizers = Collections.unmodifiableList(customizers);
    }

    @Override
    public SchemaObjectBuilder customize(SchemaObjectBuilder builder, SchemaObjectCustomizerContext context) {
        for (SchemaObjectCustomizer customizer : customizers) {
            try {
                builder = customizer.customize(builder, context);
            } catch (Exception e) {
                throw new MuException("Error while customizing " + context + " with " + customizer, e);
            }
            if (builder == null) {
                throw new IllegalStateException(customizer + " returned null. A schema object builder must always be returned");
            }
        }
        return builder;
    }
}
