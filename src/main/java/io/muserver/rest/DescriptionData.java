package io.muserver.rest;

import io.muserver.openapi.ExternalDocumentationObject;
import io.muserver.openapi.TagObject;

import java.lang.reflect.AnnotatedElement;
import java.net.URI;
import java.net.URISyntaxException;

import static io.muserver.openapi.ExternalDocumentationObjectBuilder.externalDocumentationObject;
import static io.muserver.openapi.TagObjectBuilder.tagObject;

class DescriptionData {

    final String summary;
    final String description;
    final ExternalDocumentationObject externalDocumentation;
    final String example;

    DescriptionData(String summary, String description, ExternalDocumentationObject externalDocumentation, String example) {
        this.summary = summary;
        this.description = description;
        this.externalDocumentation = externalDocumentation;
        this.example = example;
    }

    static DescriptionData fromAnnotation(AnnotatedElement source, String defaultSummary) {
        Description description = source.getAnnotation(Description.class);
        if (description == null) {
            return new DescriptionData(defaultSummary, null, null, null);
        } else {
            ExternalDocumentationObject externalDocumentation = null;
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
        return tagObject().withName(summary).withDescription(description).withExternalDocs(externalDocumentation).build();
    }

}
