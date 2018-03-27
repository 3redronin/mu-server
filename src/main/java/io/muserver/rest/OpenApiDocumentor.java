package io.muserver.rest;

import io.muserver.*;
import io.muserver.openapi.*;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static java.util.stream.Collectors.toList;

class OpenApiDocumentor implements MuHandler {
    private final Set<ResourceClass> roots;
    private final EntityProviders entityProviders;
    private final String openApiJsonUrl;

    OpenApiDocumentor(Set<ResourceClass> roots, EntityProviders entityProviders, String openApiJsonUrl) {
        this.roots = roots;
        this.entityProviders = entityProviders;
        this.openApiJsonUrl = Mutils.trim(openApiJsonUrl, "/");
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String relativePath = Mutils.trim(request.relativePath(), "/");
        if (request.method() != Method.GET || !relativePath.equals(openApiJsonUrl)) {
            return false;
        }

        Map<String, PathItemObject> pathItems = new HashMap<>();
        for (ResourceClass root : roots) {
            for (ResourceMethod method : root.resourceMethods) {
                String path = "/" + Mutils.trim(Mutils.join(root.pathTemplate, "/", method.pathTemplate), "/");
                Map<String, OperationObject> operations = new HashMap<>();
                List<ParameterObject> parameters = method.params.stream()
                    .filter(p -> p.source.openAPIIn != null && p instanceof ResourceMethodParam.RequestBasedParam)
                    .map(ResourceMethodParam.RequestBasedParam.class::cast)
                    .map(p -> parameterObject()
                        .withName(p.key)
                        .withIn(p.source.openAPIIn)
                        .withSchema(
                            schemaObject()
                                .withType(p.jsonType())
                                .withDefaultValue(p.defaultValue())
                                .withFormat(p.jsonFormat())
                                .build()
                        )
                        .build())
                    .collect(toList());
                Map<String, ResponseObject> httpStatusCodes = new HashMap<>();
                httpStatusCodes.put("200", responseObject()
                    .withDescription("Success")
                    .build());
                operations.put(method.httpMethod.name().toLowerCase(),
                    operationObject()
                        .withParameters(parameters)
                        .withResponses(
                            responsesObject()
                                .withHttpStatusCodes(httpStatusCodes)
                                .build())
                        .build());
                PathItemObject pathItem = pathItemObject()
                    .withOperations(operations)
                    .build();
                pathItems.put(path, pathItem);
            }
        }

        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject()
            .withInfo(
                infoObject()
                    .withTitle("Title")
                    .withVersion("1.0")
                    .build())
            .withServers(
                Collections.singletonList(
                    serverObject()
                        .withUrl(Mutils.trim(request.uri().resolve(request.contextPath()).toString(), "/"))
                        .build())
            )
            .withPaths(
                pathsObject()
                    .withPathItemObjects(pathItems)
                    .build()
            )
            .build();

        response.contentType(ContentTypes.APPLICATION_JSON);

        try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw, 8192)) {
            api.writeJson(writer);
        }

        return true;
    }
}
