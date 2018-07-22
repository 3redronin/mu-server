package io.muserver.rest;

import io.muserver.openapi.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.rest.SafeHtml.htmlEscape;
import static java.util.Collections.singletonMap;

class HtmlDocumentor {

    private final BufferedWriter writer;
    private final OpenAPIObject api;
    private final String css;

    HtmlDocumentor(BufferedWriter writer, OpenAPIObject api, String css) {
        this.writer = writer;
        this.api = api;
        this.css = css;
    }

    void writeHtml() throws IOException {

        writer.write("<!DOCTYPE html>\n");
        El html = new El("html").open();
        El head = new El("head").open();

        render("title", api.info.title);

        new El("style").open().contentRaw(css).close();

        head.close();

        El body = new El("body").open();

        new El("h1").open().content(api.info.title).close();

        El preamble = new El("div").open(singletonMap("class", "preamble"));

        new El("p").open().content(api.info.description).close();

        if (api.externalDocs != null) {
            String url = api.externalDocs.url.toString();
            String desc = api.externalDocs.description == null ? url : api.externalDocs.description;
            El ext = new El("p").open().content("For more info, see ");
            new El("a").open(Collections.singletonMap("href", url)).content(desc).close();
            ext.close();
        }

        El metaData = new El("p").open(singletonMap("class", "apiMetaData"));
        metaData.content("Version " + api.info.version);
        if (api.info.contact != null) {
            ContactObject contact = api.info.contact;
            metaData.content(" | Contact: ");
            if (contact.url != null) {
                String contactName = contact.name == null ? contact.url.toString() : contact.name;
                new El("a").open(Collections.singletonMap("href", contact.url.toString())).content(contactName).close();
            } else if (contact.name != null) {
                metaData.content(" " + contact.name);
            }
            if (contact.email != null) {
                metaData.content(" | ");
                new El("a").open(Collections.singletonMap("href", "mailto:" + contact.email)).content(contact.email).close();
            }
        }
        LicenseObject license = api.info.license;
        if (license != null) {
            metaData.content(" | License: ");
            String name = license.name == null ? license.url.toString() : license.name;
            new El("a").open(Collections.singletonMap("href", "mailto:" + license.url)).content(name).close();
        }

        if (api.info.termsOfService != null) {
            metaData.content(" | ");
            new El("a").open(Collections.singletonMap("href", api.info.termsOfService.toString())).content("Terms of service").close();
        }


        preamble.close();


        El nav = new El("ul").open(singletonMap("class", "nav"));
        for (TagObject tag : api.tags) {
            El li = new El("li").open();
            new El("a").open(singletonMap("href", "#" + htmlEscape(tag.name))).content(tag.name).close();

            El subNav = new El("ul").open(singletonMap("class", "subNav"));
            for (Map.Entry<String, PathItemObject> entry : api.paths.pathItemObjects.entrySet()) {
                String url = entry.getKey();
                PathItemObject item = entry.getValue();
                for (Map.Entry<String, OperationObject> operationObjectEntry : item.operations.entrySet()) {
                    String method = operationObjectEntry.getKey();
                    OperationObject operation = operationObjectEntry.getValue();
                    if (operation.tags.contains(tag.name)) {

                        El subNavLi = new El("li").open();
                        new El("a").open(singletonMap("href", "#" + htmlEscape(operation.operationId))).content(method.toUpperCase() + " " + url).close();
                        subNavLi.close();

                    }
                }
            }


            subNav.close();
            li.close();


        }
        nav.close();


        for (TagObject tag : api.tags) {
            El tagContainer = new El("div").open(singletonMap("class", "tagContainer"));
            new El("h2").open(singletonMap("id", htmlEscape(tag.name))).content(tag.name).close();
            renderIfValue("p", tag.description);

            for (Map.Entry<String, PathItemObject> entry : api.paths.pathItemObjects.entrySet()) {
                String url = entry.getKey();
                PathItemObject item = entry.getValue();
                for (Map.Entry<String, OperationObject> operationObjectEntry : item.operations.entrySet()) {
                    String method = operationObjectEntry.getKey();
                    OperationObject operation = operationObjectEntry.getValue();
                    if (operation.tags.contains(tag.name)) {

                        Map<String, String> operationAttributes = new HashMap<>();
                        operationAttributes.put("id", htmlEscape(operation.operationId));
                        operationAttributes.put("class", "operation");
                        El operationDiv = new El("div").open(operationAttributes);

                        new El("h3").open().content(method.toUpperCase() + " " + url).close();
                        renderIfValue("p", operation.summary);
                        renderIfValue("p", operation.description);

                        if (operation.deprecated) {
                            new El("p").open(singletonMap("class", "deprecated")).content("WARNING: This operation is marked as deprecated and may not be supported in future versions of this API.").close();
                        }

                        RequestBodyObject requestBody = operation.requestBody;
                        if (!operation.parameters.isEmpty()) {
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


                            for (ParameterObject parameter : operation.parameters) {
                                El row = new El("tr").open();
                                render("td", parameter.name);
                                String type = parameter.in;
                                SchemaObject schema = parameter.schema;
                                if (schema != null) {
                                    type += " - " + schema.type;
                                    if (schema.format != null) {
                                        type += " (" + schema.format + ")";
                                    }
                                }
                                render("td", type);
                                El paramDesc = new El("td").open();
                                if (parameter.deprecated) {
                                    render("strong", "DEPRECATED. ");
                                }
                                if (parameter.required) {
                                    render("strong", "REQUIRED. ");
                                }
                                paramDesc.content(parameter.description);

                                renderExamples(parameter.example, parameter.examples);

                                paramDesc.close();
                                row.close();
                            }

                            tbody.close();
                            table.close();
                        }

                        if (requestBody != null) {
                            render("h4", "Request body");

                            renderIfValue("p", requestBody.description);

                            for (Map.Entry<String, MediaTypeObject> bodyEntry : requestBody.content.entrySet()) {
                                String mediaType = bodyEntry.getKey();
                                MediaTypeObject value = bodyEntry.getValue();
                                render("h5", mediaType);

                                renderExamples(value.example, value.examples);

                                if (value.schema == null) {
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


                                List<String> requiredParams = value.schema.required;
                                for (Map.Entry<String, SchemaObject> props : value.schema.properties.entrySet()) {
                                    String formName = props.getKey();
                                    SchemaObject schema = props.getValue();
                                    El row = new El("tr").open();
                                    render("td", formName);

                                    String type = schema.type;
                                    if (schema.format != null) {
                                        type += " (" + schema.format + ")";
                                    }
                                    render("td", type);

                                    El paramDesc = new El("td").open();
                                    if (schema.deprecated) {
                                        render("strong", "DEPRECATED. ");
                                    }
                                    if (requiredParams != null && requiredParams.contains(formName)) {
                                        render("strong", "REQUIRED. ");
                                    }
                                    paramDesc.content(schema.description);

                                    if (schema.example != null) {
                                        renderExamples(schema.example, null);
                                    }

                                    paramDesc.close();
                                    row.close();

                                }

                                tbody.close();
                                table.close();
                            }



                        }


                        if (!operation.responses.httpStatusCodes.isEmpty()) {
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


                            for (Map.Entry<String, ResponseObject> respEntry : operation.responses.httpStatusCodes.entrySet()) {
                                El row = new El("tr").open();
                                String code = respEntry.getKey();
                                ResponseObject resp = respEntry.getValue();
                                render("td", code);
                                render("td", resp.content.keySet().stream().collect(Collectors.joining("\n")));
                                render("td", resp.description);


                                row.close();
                            }

                            tbody.close();
                            table.close();
                        }


                        operationDiv.close();
                    }
                }
            }
            tagContainer.close();
        }

        body.close();
        html.close();
    }

    private void renderExamples(Object example, Map<String, ExampleObject> examples) throws IOException {
        if (example != null) {
            El div = new El("div").open().content("Example: ");
            render("code", example.toString());
            div.close();
        } else if (examples != null) {
            for (Map.Entry<String, ExampleObject> exampleEntry : examples.entrySet()) {
                El div = new El("div").open();

                new El("code").open().content(exampleEntry.getKey()).close();
                ExampleObject ex = exampleEntry.getValue();
                new El("span").open().content(" ", ex.summary, " ", ex.description).close();
                new El("pre").open().content(ex.value).close();

                div.close();
            }
        }
    }

    private void renderIfValue(String tag, String value) throws IOException {
        if (value != null) {
            new El(tag).open().content(value).close();
        }
    }

    private void render(String tag, String value) throws IOException {
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

        El open(Map<String, String> attributes) throws IOException {
            writer.write("<" + tag);
            if (attributes != null) {
                for (Map.Entry<String, String> entry : attributes.entrySet()) {
                    writer.write(" " + htmlEscape(entry.getKey()) + "=\"" + htmlEscape(entry.getValue()) + "\"");
                }
            }
            writer.write('>');
            return this;
        }

        El contentRaw(String val) throws IOException {
            writer.write(val);
            return this;
        }

        El content(Object... vals) throws IOException {
            if (vals != null) {
                for (Object val : vals) {
                    if (val != null) {
                        String stringVal = val.toString();
                        writer.write(htmlEscape(stringVal).replace("\n", "<br>"));
                    }
                }
            }
            return this;
        }

        public void close() throws IOException {
            writer.write("</" + tag + ">");
        }


    }
}
