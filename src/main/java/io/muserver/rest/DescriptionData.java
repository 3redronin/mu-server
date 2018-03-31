package io.muserver.rest;

import io.muserver.Description;
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

    private DescriptionData(String summary, String description, ExternalDocumentationObject externalDocumentation) {
        this.summary = summary;
        this.description = description;
        this.externalDocumentation = externalDocumentation;
    }

    public static DescriptionData fromAnnotation(AnnotatedElement source, String defaultSummary) {
        Description description = source.getAnnotation(Description.class);
        if (description == null) {
            return new DescriptionData(defaultSummary, null, null);
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

            return new DescriptionData(summary.isEmpty() ? defaultSummary : summary, desc.isEmpty() ? null : desc, externalDocumentation);

        }
    }

    public TagObject toTag() {
        return tagObject().withName(summary).withDescription(description).withExternalDocs(externalDocumentation).build();
    }

}
