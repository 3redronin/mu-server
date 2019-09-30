package io.muserver.rest;

import io.muserver.Mutils;
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

    TagObject toTag(String tagName) {
        Mutils.notNull("tagName", tagName);
        String description = summaryAndDescription();
        return tagObject()
            .withName(tagName)
            .withDescription(tagName.equals(description) ? null : description)
            .withExternalDocs(externalDocumentation)
            .build();
    }

    public String summaryAndDescription() {
        String s = Mutils.coalesce(summary, "");
        if (!Mutils.nullOrEmpty(description)) {
            s += " - " + description;
        }
        return s;
    }
}
