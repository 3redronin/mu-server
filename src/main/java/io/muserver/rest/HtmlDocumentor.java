package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.openapi.*;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.muserver.Mutils.urlEncode;
import static java.util.Collections.singletonMap;

class HtmlDocumentor {

    private final BufferedWriter writer;
    private final OpenAPIObject api;
    private final DocumentationReferences references;
    private final String css;
    private final URI requestUri;

    HtmlDocumentor(BufferedWriter writer, OpenAPIObject api, String css, URI requestUri) {
        this.writer = writer;
        this.api = api;
        this.references = new DocumentationReferences(api);
        this.css = css;
        this.requestUri = requestUri;
    }

    void writeHtml() throws IOException {

        writer.write("<!DOCTYPE html>\n");
        El html = new El("html").open();
        El head = new El("head").open();

        render("title", api.info().title());

        new El("style").open().contentRaw(css).close();

        head.close();

        El body = new El("body").open();

        new El("h1").open().content(api.info().title()).close();

        El preamble = new El("div").open(singletonMap("class", "preamble"));

        renderIfValue("p", api.info().description());

        renderExternalLinksParagraph(api.externalDocs());

        El metaData = new El("p").open(singletonMap("class", "apiMetaData"));
        metaData.content("Version " + api.info().version());
        if (api.info().contact() != null) {
            ContactObject contact = api.info().contact();
            metaData.content(" | Contact: ");
            if (contact.url() != null) {
                String contactName = contact.name() == null ? contact.url().toString() : contact.name();
                new El("a").open(Collections.singletonMap("href", contact.url().toString())).content(contactName).close();
            } else if (contact.name() != null) {
                metaData.content(" " + contact.name());
            }
            if (contact.email() != null) {
                metaData.content(" | ");
                new El("a").open(Collections.singletonMap("href", "mailto:" + contact.email())).content(contact.email()).close();
            }
        }
        LicenseObject license = api.info().license();
        if (license != null) {
            metaData.content(" | License: ");
            URI licenseUrl = license.url();
            if (licenseUrl == null) {
                metaData.content(license.name());
            } else {
                new El("a").open(Collections.singletonMap("href", licenseUrl.toString())).content(license.name()).close();
            }
        }

        if (api.info().termsOfService() != null) {
            metaData.content(" | ");
            new El("a").open(Collections.singletonMap("href", api.info().termsOfService().toString())).content("Terms of service").close();
        }

        String baseUri = "";
        if (api.servers() != null && !api.servers().isEmpty()) {
            baseUri = api.servers().get(0).url();
        }

        preamble.close();


        for (Map.Entry<String, PathItemObject> entry : pathItems().entrySet()) {
            if (entry.getValue().ref() != null) render("p", entry.getKey() + " Reference: " + entry.getValue().ref());
        }
        El nav = new El("ul").open(singletonMap("class", "nav operation"));
        List<TagObject> tags = new ArrayList<>(api.tags() == null ? Collections.emptyList() : api.tags());
        Set<String> tagNames = new HashSet<>();
        for (TagObject tag : tags) tagNames.add(tag.name());
        for (PathItemObject item : pathItems().values()) for (OperationObject operation : operations(item).values()) {
            for (String name : operationTags(operation)) if (tagNames.add(name)) tags.add(TagObjectBuilder.tagObject().withName(name).build());
        }
        for (TagObject tag : tags) {
            El li = new El("li").open();
            new El("a").open(singletonMap("href", "#" + Mutils.htmlEncode(tag.name()))).content(tag.summary() == null ? tag.name() : tag.summary()).close();

            El subNav = new El("ul").open(singletonMap("class", "subNav"));
            for (Map.Entry<String, PathItemObject> entry : pathItems().entrySet()) {
                String url = entry.getKey();
                PathItemObject item = entry.getValue();
                for (Map.Entry<String, OperationObject> operationObjectEntry : operations(item).entrySet()) {
                    String method = operationObjectEntry.getKey();
                    OperationObject operation = operationObjectEntry.getValue();
                    if (operationTags(operation).contains(tag.name())) {

                        El subNavLi = new El("li").open();
                        new El("a").open(singletonMap("href", "#" + Mutils.htmlEncode(operation.operationId()))).content(method.toUpperCase(Locale.ROOT) + " " + url).close();
                        subNavLi.close();

                    }
                }
            }


            subNav.close();
            li.close();


        }
        nav.close();


        for (TagObject tag : tags) {
            El tagContainer = new El("div").open(singletonMap("class", "tagContainer"));
            new El("h2").open(singletonMap("id", Mutils.htmlEncode(tag.name()))).content(tag.summary() == null ? tag.name() : tag.summary()).close();
            renderIfValue("p", tag.description());
            renderExternalLinksParagraph(tag.externalDocs());

            for (Map.Entry<String, PathItemObject> entry : pathItems().entrySet()) {
                String url = entry.getKey();
                PathItemObject item = entry.getValue();
                for (Map.Entry<String, OperationObject> operationObjectEntry : operations(item).entrySet()) {
                    String method = operationObjectEntry.getKey();
                    OperationObject operation = operationObjectEntry.getValue();

                    if (operationTags(operation).contains(tag.name())) {

                        Map<String, String> operationAttributes = new HashMap<>();
                        operationAttributes.put("id", Mutils.htmlEncode(operation.operationId()));
                        operationAttributes.put("class", "operation");
                        El operationDiv = new El("div").open(operationAttributes);

                        El h3 = new El("h3").open().content(method.toUpperCase(Locale.ROOT) + " ");
                        String urlWithContext = baseUri + url;
                        new El("a").open(Collections.singletonMap("href", urlWithContext)).content(url).close();
                        h3.close();
                        renderIfValue("p", operation.summary());
                        renderIfValue("p", operation.description());
                        renderExternalLinksParagraph(operation.externalDocs());

                        if (operation.isDeprecated()) {
                            new El("p").open(singletonMap("class", "deprecated")).content("WARNING: This operation is marked as deprecated and may not be supported in future versions of this API.").close();
                        }

                        StringBuilder queryString = new StringBuilder();
                        StringBuilder curlHeaders = new StringBuilder();

                        RequestBodyObject requestBody = references.resolve(operation.requestBodyOrReferences(), RequestBodyObject.class);
                        renderReference(operation.requestBodyOrReferences());
                        List<ParameterObject> parameters = new ArrayList<>();
                        if (operation.parametersOrReferences() != null) for (ReferenceOr<ParameterObject> parameterRef : operation.parametersOrReferences()) {
                            renderReference(parameterRef);
                            ParameterObject parameter = references.resolve(parameterRef, ParameterObject.class);
                            if (parameter != null) parameters.add(parameter);
                        }
                        if (!parameters.isEmpty()) {
                            render("h4", "Parameters");
                            El table = new El("table").open(singletonMap("class", "parameterTable"));

                            El thead = new El("thead").open();
                            El theadRow = new El("tr").open();
                            render("th", "Name");
                            render("th", "Type");
                            render("th", "Description");
                            theadRow.close();
                            thead.close();

                            El tbody = new El("tbody").open();


                            for (ParameterObject parameter : parameters) {
                                El row = new El("tr").open();
                                render("td", parameter.name());
                                String type = parameter.in();

                                SchemaObject schema = resolvedSchema(parameter.schema());
                                Object sample = parameter.example() == null ? schemaExample(schema) : parameter.example();
                                if ("query".equals(type)) {
                                    appendQuery(queryString, parameter, sample);
                                } else if ("header".equals(type)) {
                                    curlHeaders.append(" -H '").append(bashValue(parameter.name())).append(": ")
                                        .append(bashValue(sample instanceof Collection ? joinSample((Collection<?>) sample, ",") : sample)).append('\'');
                                } else if ("cookie".equals(type)) {
                                    curlHeaders.append(" -H 'Cookie: ").append(bashValue(parameter.name())).append("=").append(bashValue(sample)).append('\'');
                                }

                                @Nullable Object defaultVal = null;
                                @Nullable ExternalDocumentationObject externalDocs = null;
                                if (schema != null) {
                                    type += " - " + schemaType(schema);
                                    if (schema.format() != null) {
                                        type += " (" + schema.format() + ")";
                                    }
                                    defaultVal = schema.defaultValue();
                                    externalDocs = schema.externalDocs();
                                }
                                render("td", type);
                                El paramDesc = new El("td").open();
                                if (parameter.deprecated()) {
                                    render("strong", "DEPRECATED. ");
                                }
                                if (parameter.required()) {
                                    render("strong", "REQUIRED. ");
                                }
                                paramDesc.content(parameter.description());

                                renderExamples(sample, resolvedExamples(parameter.examplesOrReferences()), defaultVal);
                                renderExternalLinksParagraph(externalDocs);

                                paramDesc.close();
                                row.close();
                            }

                            tbody.close();
                            table.close();
                        }

                        StringBuilder curlBody = new StringBuilder();
                        if (requestBody != null) {
                            render("h4", "Request body");

                            renderIfValue("p", requestBody.description());

                            for (Map.Entry<String, MediaTypeObject> bodyEntry : resolvedContent(requestBody.contentOrReferences()).entrySet()) {
                                String mediaType = bodyEntry.getKey();
                                MediaTypeObject original = bodyEntry.getValue();
                                MediaTypeObject value = original.toBuilder().withSchema(resolvedSchema(original.schema())).build();
                                boolean curlAlternative = curlBody.length() == 0;
                                boolean formEncoding = mediaType.equalsIgnoreCase(MediaType.MULTIPART_FORM_DATA) || mediaType.equalsIgnoreCase(MediaType.APPLICATION_FORM_URLENCODED);
                                render("h5", mediaType);

                                renderExamples(value.example() == null ? schemaExample(value.schema()) : value.example(), resolvedExamples(value.examplesOrReferences()), value.schema() == null ? null : value.schema().defaultValue());
                                renderIfValue("p", value.schema() == null ? null : schemaType(value.schema()));

                                String curlFormParam = (mediaType.equalsIgnoreCase(MediaType.MULTIPART_FORM_DATA)) ? "-F" : "--data-urlencode";
                                if (curlAlternative) {
                                    curlBody = new StringBuilder(" -H 'content-type: " + mediaType + "'");
                                }

                                if (!formEncoding || value.schema() == null || value.schema().properties() == null) {
                                    if (curlAlternative) curlBody.append(" --data-binary '").append(bashValue(value.example() == null ? schemaExample(value.schema()) : value.example())).append("'");
                                    continue;
                                }

                                El table = new El("table").open(singletonMap("class", "parameterTable"));
                                El thead = new El("thead").open();
                                El theadRow = new El("tr").open();
                                render("th", "Name");
                                render("th", "Type");
                                render("th", "Description");
                                theadRow.close();
                                thead.close();

                                El tbody = new El("tbody").open();


                                List<String> requiredParams = value.schema().required();
                                for (Map.Entry<String, SchemaObject> props : value.schema().properties().entrySet()) {
                                    String formName = props.getKey();
                                    SchemaObject schema = Objects.requireNonNull(resolvedSchema(props.getValue()));
                                    El row = new El("tr").open();
                                    render("td", formName);

                                    @Nullable String type = schemaType(schema);
                                    if (schema.format() != null) {
                                        type += " (" + schema.format() + ")";
                                    }
                                    render("td", type);

                                    El paramDesc = new El("td").open();
                                    if (schema.isDeprecated()) {
                                        render("strong", "DEPRECATED. ");
                                    }
                                    if (requiredParams != null && requiredParams.contains(formName)) {
                                        render("strong", "REQUIRED. ");
                                    }
                                    paramDesc.content(schema.description());
                                    renderExamples(schemaExample(schema), null, schema.defaultValue());


                                    if (curlAlternative) {
                                        EncodingObject encoding = value.encoding() == null ? null : value.encoding().get(formName);
                                        Object sample = schemaExample(schema);
                                        if ("-F".equals(curlFormParam) && encoding != null && "application/octet-stream".equals(encoding.contentType()) && sample == null) sample = "@file.bin";
                                        Collection<?> samples = sample instanceof Collection ? (Collection<?>) sample : Collections.singletonList(sample);
                                        for (Object itemSample : samples) curlBody.append(" ").append(curlFormParam).append(" '").append(bashValue(formName)).append("=").append(bashValue(itemSample)).append("'");
                                    }

                                    paramDesc.close();
                                    row.close();
                                }

                                tbody.close();
                                table.close();
                            }


                        }

                        String curlAccept = "";
                        boolean streaming = false;
                        Map<String, ResponseObject> responses = new LinkedHashMap<>();
                        if (operation.responses() != null) {
                            Map<String, ReferenceOr<ResponseObject>> values = new LinkedHashMap<>(operation.responses().httpStatusCodesOrReferences());
                            if (operation.responses().defaultValueOrReferences() != null) values.put("default", operation.responses().defaultValueOrReferences());
                            for (Map.Entry<String, ReferenceOr<ResponseObject>> response : values.entrySet()) {
                                renderReference(response.getValue());
                                ResponseObject resolved = references.resolve(response.getValue(), ResponseObject.class);
                                if (resolved != null) responses.put(response.getKey(), resolved);
                            }
                        }
                        if (!responses.isEmpty()) {
                            render("h4", "Responses");


                            El table = new El("table").open(singletonMap("class", "responseTable"));

                            El thead = new El("thead").open();
                            El theadRow = new El("tr").open();
                            render("th", "Code");
                            render("th", "Content Type");
                            render("th", "Description");
                            theadRow.close();
                            thead.close();

                            El tbody = new El("tbody").open();


                            for (Map.Entry<String, ResponseObject> respEntry : responses.entrySet()) {
                                El row = new El("tr").open();
                                String code = respEntry.getKey();
                                ResponseObject resp = respEntry.getValue();
                                render("td", code);
                                String contentTypes = resp.contentOrReferences() == null ? "" : String.join("\n", resp.contentOrReferences().keySet());
                                render("td", contentTypes);
                                El details = new El("td").open();
                                render("p", resp.summary());
                                render("p", resp.description());
                                for (Map.Entry<String, MediaTypeObject> contentEntry : resolvedContent(resp.contentOrReferences()).entrySet()) {
                                    MediaTypeObject media = contentEntry.getValue();
                                    render("p", media.description());
                                    if (media.itemSchema() != null) {
                                        streaming = true;
                                        render("p", "Parsed stream item (" + contentEntry.getKey() + ")");
                                        render("pre", String.valueOf(resolvedSchema(media.itemSchema())));
                                    }
                                    renderExamples(media.example(), resolvedExamples(media.examplesOrReferences()), null);
                                }
                                details.close();
                                if (curlAccept.isEmpty() && !contentTypes.isEmpty()) {
                                    curlAccept = " -H 'accept: " + contentTypes.split("\n", 2)[0] + "'";
                                }


                                row.close();
                            }

                            tbody.close();
                            table.close();
                        }

                        render("h4", "Curl");
                        String sampleUrl = urlWithContext.replace("{", "(").replace("}", ")")
                            + queryString;
                        render("code", "curl " + (streaming ? "-N " : "") + "-is -X " + method.toUpperCase(Locale.ROOT) + curlHeaders + curlAccept +
                            curlBody + " '" + requestUri.resolve(sampleUrl) + "'");


                        operationDiv.close();
                    }
                }
            }
            tagContainer.close();
        }

        body.close();
        html.close();
    }

    private Map<String, PathItemObject> pathItems() {
        Map<String, PathItemObject> paths = api.paths() == null ? null : api.paths().pathItemObjects();
        Map<String, PathItemObject> resolved = new LinkedHashMap<>();
        if (paths != null) paths.forEach((key, value) -> {
            PathItemObject target = references.path(value);
            resolved.put(key, target == null ? value : target);
        });
        return resolved;
    }

    private static Map<String, OperationObject> operations(PathItemObject item) {
        Map<String, OperationObject> operations = new LinkedHashMap<>();
        if (item.operations() != null) operations.putAll(item.operations());
        if (item.additionalOperations() != null) operations.putAll(item.additionalOperations());
        return operations;
    }

    private static List<String> operationTags(OperationObject operation) {
        List<String> tags = operation.tags();
        return tags == null || tags.isEmpty() ? Collections.singletonList("Operations") : tags;
    }

    private static String bashValue(@Nullable Object value) {
        if (value == null || "".equals(value)) return "";
        String text = value.toString();
        if (value instanceof Map || value instanceof Collection || value.getClass().isArray() || value == JsonNull.INSTANCE) {
            java.io.StringWriter writer = new java.io.StringWriter();
            try { Jsonizer.writeValue(writer, value); } catch (IOException e) { throw new IllegalStateException(e); }
            text = writer.toString();
        }
        return text.replace("'", "'\\''");
    }

    private static void appendQuery(StringBuilder query, ParameterObject parameter, @Nullable Object sample) {
        String style = parameter.style() == null ? "form" : parameter.style();
        if (sample instanceof Map && "deepObject".equals(style)) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) sample).entrySet()) appendQueryValue(query, parameter.name() + "[" + entry.getKey() + "]", entry.getValue());
        } else if (sample instanceof Collection && parameter.explode()) {
            for (Object item : (Collection<?>) sample) appendQueryValue(query, parameter.name(), item);
        } else {
            String delimiter = "spaceDelimited".equals(style) ? " " : "pipeDelimited".equals(style) ? "|" : ",";
            appendQueryValue(query, parameter.name(), sample instanceof Collection ? joinSample((Collection<?>) sample, delimiter) : sample);
        }
    }

    private static String joinSample(Collection<?> sample, String delimiter) {
        List<String> values = new ArrayList<>();
        for (Object value : sample) values.add(String.valueOf(value));
        return String.join(delimiter, values);
    }

    private static void appendQueryValue(StringBuilder query, String name, @Nullable Object value) {
        query.append(query.length() == 0 ? '?' : '&').append(urlEncode(name)).append('=')
            .append(urlEncode(value == null ? "" : value.toString()));
    }

    private Map<String, MediaTypeObject> resolvedContent(@Nullable Map<String, ReferenceOr<MediaTypeObject>> content) throws IOException {
        Map<String, MediaTypeObject> result = new LinkedHashMap<>();
        if (content != null) for (Map.Entry<String, ReferenceOr<MediaTypeObject>> entry : content.entrySet()) {
            renderReference(entry.getValue());
            MediaTypeObject value = references.resolve(entry.getValue(), MediaTypeObject.class);
            if (value != null) result.put(entry.getKey(), value);
        }
        return result;
    }

    private @Nullable SchemaObject resolvedSchema(@Nullable SchemaObject schema) {
        SchemaObject resolved = references.schema(schema);
        return resolved == null ? schema : resolved;
    }

    private static String schemaType(SchemaObject schema) {
        if (schema.booleanValue() != null) return schema.booleanValue() ? "any value" : "no value";
        if (schema.ref() != null) return "Reference: " + schema.ref();
        return schema.types() == null ? "any value" : String.join(" | ", schema.types());
    }

    private static @Nullable Object schemaExample(@Nullable SchemaObject schema) {
        if (schema == null) return null;
        if (schema.examples() != null && !schema.examples().isEmpty()) return schema.examples().get(0);
        if (schema.example() != null) return schema.example();
        if (schema.properties() != null) {
            Map<String, Object> example = new LinkedHashMap<>();
            schema.properties().forEach((key, value) -> {
                Object sample = schemaExample(value);
                if (sample != null) example.put(key, sample);
            });
            return example;
        }
        return schema.defaultValue();
    }

    private void renderReference(@Nullable ReferenceOr<?> value) throws IOException {
        if (value != null && value.isReference()) {
            ReferenceObject ref = Objects.requireNonNull(value.reference());
            render("p", "Reference: " + ref.ref());
            renderIfValue("p", ref.summary());
            renderIfValue("p", ref.description());
        }
    }

    private @Nullable Map<String, ExampleObject> resolvedExamples(@Nullable Map<String, ReferenceOr<ExampleObject>> values) throws IOException {
        if (values == null) return null;
        Map<String, ExampleObject> result = new LinkedHashMap<>();
        for (Map.Entry<String, ReferenceOr<ExampleObject>> entry : values.entrySet()) {
            renderReference(entry.getValue());
            ExampleObject example = references.resolve(entry.getValue(), ExampleObject.class);
            if (example != null) result.put(entry.getKey(), example);
        }
        return result;
    }

    private void renderExternalLinksParagraph(@Nullable ExternalDocumentationObject externalDocs) throws IOException {
        if (externalDocs != null) {
            String url = externalDocs.url().toString();
            String desc = externalDocs.description() == null ? url : externalDocs.description();
            El ext = new El("p").open().content("For more info, see ");
            new El("a").open(Collections.singletonMap("href", url)).content(desc).close();
            ext.close();
        }
    }

    private void renderExamples(@Nullable Object example, @Nullable Map<String, ExampleObject> examples, @Nullable Object defaultVal) throws IOException {
        if (example != null) {
            El div = new El("div").open().content("Example: ");
            render("code", example.toString());
            div.close();
        } else if (examples != null) {
            for (Map.Entry<String, ExampleObject> exampleEntry : examples.entrySet()) {
                El div = new El("div").open();

                new El("code").open().content(exampleEntry.getKey()).close();
                ExampleObject ex = exampleEntry.getValue();
                new El("span").open().content(" ", ex.summary(), " ", ex.description()).close();
                if (ex.dataValue() != null) { render("p", "Parsed value"); render("pre", String.valueOf(ex.dataValue())); }
                if (ex.serializedValue() != null) { render("p", "Serialized value"); render("pre", ex.serializedValue()); }
                new El("pre").open().content(ex.value()).close();

                div.close();
            }
        }
        if (defaultVal != null) {
            El div = new El("div").open().content("Default value: ");
            render("code", defaultVal.toString());
            div.close();
        }
    }

    private void renderIfValue(String tag, @Nullable String value) throws IOException {
        if (value != null) {
            new El(tag).open().content(value).close();
        }
    }

    private void render(String tag, @Nullable String value) throws IOException {
        new El(tag).open().content(value).close();
    }


    class El implements AutoCloseable {
        private final String tag;

        private El(String tag) {
            this.tag = tag;
        }

        El open() throws IOException {
            return open(null);
        }

        El open(@Nullable Map<String, String> attributes) throws IOException {
            writer.write("<" + tag);
            if (attributes != null) {
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    writer.write(" " + Mutils.htmlEncode(entry.getKey()) + "=\"" + Mutils.htmlEncode(entry.getValue()) + "\"");
                }
            }
            writer.write('>');
            return this;
        }

        El contentRaw(String val) throws IOException {
            writer.write(val);
            return this;
        }

        El content(@Nullable Object... vals) throws IOException {
            if (vals != null) {
                for (Object val : vals) {
                    if (val != null) {
                        String stringVal = val.toString();
                        writer.write(Mutils.htmlEncode(stringVal).replace("\n", "<br>"));
                    }
                }
            }
            return this;
        }

        @Override
        public void close() throws IOException {
            writer.write("</" + tag + ">");
        }


    }
}
