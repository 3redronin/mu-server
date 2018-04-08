package io.muserver.rest;

import io.muserver.*;
import io.muserver.openapi.*;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.MediaTypeObjectBuilder.mediaTypeObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.RequestBodyObjectBuilder.requestBodyObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;

class OpenApiDocumentor implements MuHandler {
    private final Set<ResourceClass> roots;
    private final EntityProviders entityProviders;
    private final String openApiJsonUrl;
    private final OpenAPIObject openAPIObject;
    private final String openApiHtmlUrl = "openapi.html";
    private final String openApiHtmlCss;

    OpenApiDocumentor(Set<ResourceClass> roots, EntityProviders entityProviders, String openApiJsonUrl, OpenAPIObject openAPIObject, String openApiHtmlCss) {
        notNull("openAPIObject", openAPIObject);
        this.roots = roots;
        this.entityProviders = entityProviders;
        this.openApiJsonUrl = Mutils.trim(openApiJsonUrl, "/");
        this.openAPIObject = openAPIObject;
        this.openApiHtmlCss = openApiHtmlCss;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String relativePath = Mutils.trim(request.relativePath(), "/");
        if (request.method() != Method.GET || (!relativePath.equals(openApiJsonUrl) && !relativePath.equals(openApiHtmlUrl))) {
            return false;
        }

        List<TagObject> tags = new ArrayList<>();

        Map<String, PathItemObject> pathItems = new HashMap<>();
        for (ResourceClass root : roots) {

            tags.add(root.tag);


            for (ResourceMethod method : root.resourceMethods) {
                String path = "/" + Mutils.trim(Mutils.join(root.pathTemplate, "/", method.pathTemplate), "/");

                Map<String, OperationObject> operations;
                if (pathItems.containsKey(path)) {
                    operations = pathItems.get(path).operations;
                } else {
                    operations = new HashMap<>();
                    PathItemObject pathItem = pathItemObject()
                        .withOperations(operations)
                        .build();
                    pathItems.put(path, pathItem);
                }
                List<ParameterObject> parameters = method.params.stream()
                    .filter(p -> p.source.openAPIIn != null && p instanceof ResourceMethodParam.RequestBasedParam)
                    .map(ResourceMethodParam.RequestBasedParam.class::cast)
                    .map(p -> p.createDocumentationBuilder().build())
                    .collect(toList());


                operations.put(method.httpMethod.name().toLowerCase(),
                    method.createOperationBuilder()
                        .withTags(singletonList(root.tag.name))
                        .withParameters(parameters)
                        .withRequestBody(
                            method.params.stream()
                                .filter(p -> p instanceof ResourceMethodParam.MessageBodyParam).map(ResourceMethodParam.MessageBodyParam.class::cast)
                                .map(messageBodyParam -> requestBodyObject()
                                    .withContent(singletonMap(method.effectiveConsumes.get(0).toString(), mediaTypeObject()
                                        .build()))
                                    .withDescription(messageBodyParam.descriptionData.description)
                                    .build())
                                .findFirst().orElse(null)
                        )
                        .build());
            }
        }

        OpenAPIObjectBuilder api = OpenAPIObjectBuilder.openAPIObject();

        if (openAPIObject.info == null) {
            api.withInfo(infoObject()
                .withTitle("Title")
                .withVersion("1.0")
                .build());
        } else {
            api.withInfo(openAPIObject.info);
        }


        api.withExternalDocs(openAPIObject.externalDocs);
        api.withSecurity(openAPIObject.security);


        api.withServers(
            singletonList(
                serverObject()
                    .withUrl(Mutils.trim(request.uri().resolve(request.contextPath()).toString(), "/"))
                    .build())
        );

        api
            .withPaths(
                pathsObject()
                    .withPathItemObjects(pathItems)
                    .build()
            )
            .withTags(tags)
            .build();

        OpenAPIObject builtApi = api.build();


        if (relativePath.equals(openApiJsonUrl)) {
            response.contentType(ContentTypes.APPLICATION_JSON);

            try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw, 8192)) {
                builtApi.writeJson(writer);
            }
        } else {
            response.contentType(ContentTypes.TEXT_HTML);
            response.headers().set("X-UA-Compatible", "IE=edge");

            try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw, 8192)) {
                new HtmlDocumentor(writer, builtApi, openApiHtmlCss).writeHtml();
            }
        }

        return true;
    }

}
