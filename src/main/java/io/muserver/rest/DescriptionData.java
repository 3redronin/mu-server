package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.openapi.ExternalDocumentationObject;
import io.muserver.openapi.TagObject;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.AnnotatedElement;
import java.net.URI;
import java.net.URISyntaxException;

import static io.muserver.openapi.ExternalDocumentationObjectBuilder.externalDocumentationObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;

class DescriptionData {

    final @Nullable String summary;
    final @Nullable String description;
    final @Nullable ExternalDocumentationObject externalDocumentation;
    final @Nullable String example;

    DescriptionData(@Nullable String summary, @Nullable String description, @Nullable ExternalDocumentationObject externalDocumentation, @Nullable String example) {
        this.summary = summary;
        this.description = description;
        this.externalDocumentation = externalDocumentation;
        this.example = example;
    }

    static DescriptionData fromAnnotation(AnnotatedElement source, @Nullable String defaultSummary) {
        Description description = source.getAnnotation(Description.class);
        if (description == null) {
            return new DescriptionData(defaultSummary, null, null, null);
        } else {
            @Nullable ExternalDocumentationObject externalDocumentation = null;
            if (!description.documentationUrl().isEmpty()) {
                try {
                    URI uri = new URI(description.documentationUrl());
                    externalDocumentation = externalDocumentationObject().withUrl(uri).build();
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException("The class " + source + " specifies a documentationUrl however it is not a value URL. The value is " + description.documentationUrl());
                }
            }
            String summary = description.value();
            String desc = description.details();
            String example = description.example();
            return new DescriptionData(summary.isEmpty() ? defaultSummary : summary, desc.isEmpty() ? null : desc, externalDocumentation, example.isEmpty() ? null : example);

        }
    }

    TagObject toTag() {
        Mutils.notNull("summary", summary);
        return tagObject()
            .withName(java.util.Objects.requireNonNull(summary))
            .withDescription(description)
            .withExternalDocs(externalDocumentation)
            .build();
    }

    public String summaryAndDescription() {
        String s = summary == null ? "" : summary;
        String details = description;
        if (details != null && !details.isEmpty()) {
            s += " - " + details;
        }
        return s;
    }
}
