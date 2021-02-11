package io.muserver.rest;

import io.muserver.*;
import io.muserver.openapi.*;

import javax.ws.rs.ext.ParamConverterProvider;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.RequestBodyObjectBuilder.requestBodyObject;
import static io.muserver.openapi.ResponsesObjectBuilder.mergeResponses;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

class OpenApiDocumentor implements MuHandler {
    private final List<ResourceClass> roots;
    private final String openApiJsonUrl;
    private final OpenAPIObject openAPIObject;
    private final String openApiHtmlUrl;
    private final String openApiHtmlCss;
    private final CORSConfig corsConfig;
    private final List<SchemaReference> customSchemas;
    private final SchemaObjectCustomizer schemaObjectCustomizer;
    private final List<ParamConverterProvider> paramConverterProviders;

    OpenApiDocumentor(List<ResourceClass> roots, String openApiJsonUrl, String openApiHtmlUrl, OpenAPIObject openAPIObject, String openApiHtmlCss, CORSConfig corsConfig, List<SchemaReference> customSchemas, SchemaObjectCustomizer schemaObjectCustomizer, List<ParamConverterProvider> paramConverterProviders) {
        this.customSchemas = customSchemas;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.paramConverterProviders = paramConverterProviders;
        notNull("openAPIObject", openAPIObject);
        this.corsConfig = corsConfig;
        this.roots = roots;
        this.openApiJsonUrl = openApiJsonUrl == null ? null : Mutils.trim(openApiJsonUrl, "/");
        this.openApiHtmlUrl = openApiHtmlUrl == null ? null : Mutils.trim(openApiHtmlUrl, "/");
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

        Map<String, PathItemObject> pathItems = new LinkedHashMap<>();
        for (ResourceClass root : roots) {
            addResourceClass(0, "", tags, pathItems, root);
        }


        OpenAPIObjectBuilder api = OpenAPIObjectBuilder.openAPIObject()
            .withInfo(openAPIObject.info())
            .withExternalDocs(openAPIObject.externalDocs())
            .withSecurity(openAPIObject.security())
            .withComponents(openAPIObject.components())
            .withServers(openAPIObject.servers() != null ? openAPIObject.servers() :
                request.contextPath().length() > 0 ?
                    singletonList(
                        serverObject()
                            .withUrl(request.contextPath())
                            .build())
                    : null
            )
            .withPaths(pathsObject().withPathItemObjects(pathItems).build())
            .withTags(tags);

        OpenAPIObject builtApi = api.build();


        if (relativePath.equals(openApiJsonUrl)) {
            response.contentType(ContentTypes.APPLICATION_JSON);
            corsConfig.writeHeadersInternal(request, response, emptySet());
            response.headers().set("Access-Control-Allow-Methods", "GET");

            try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw, 8192)) {
                builtApi.writeJson(writer);
            }
        } else {
            response.contentType(ContentTypes.TEXT_HTML_UTF8);
            response.headers().set("X-UA-Compatible", "IE=edge");

            try (OutputStreamWriter osw = new OutputStreamWriter(response.outputStream(), StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw, 8192)) {
                new HtmlDocumentor(writer, builtApi, openApiHtmlCss, request.uri()).writeHtml();
            }
        }

        return true;
    }

    private void addResourceClass(int recursiveLevel, String parentResourcePath, List<TagObject> tags, Map<String, PathItemObject> pathItems, ResourceClass root) {
        if (recursiveLevel == 5) {
            return;
        }
        if (!tags.contains(root.tag)) {
            tags.add(root.tag);
        }

        for (ResourceMethod method : root.resourceMethods) {
            if (method.isSubResourceLocator()) {
                ResourceClass rc = ResourceClass.forSubResourceLocator(method, method.methodHandle.getReturnType(), null, schemaObjectCustomizer, paramConverterProviders);
                String newParentResourcePath = Mutils.join(parentResourcePath, "/", method.resourceClass.pathPattern.pathWithoutRegex);
                addResourceClass(recursiveLevel + 1, newParentResourcePath, tags, pathItems, rc);
                continue;
            }

            String path = getPathWithoutRegex(root, method, parentResourcePath);

            Map<String, OperationObject> operations;
            if (pathItems.containsKey(path)) {
                operations = pathItems.get(path).operations();
            } else {
                operations = new LinkedHashMap<>();
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

            String opIdPath = getPathWithoutRegex(root, method, parentResourcePath).replace("{", "_").replace("}", "_");
            String opPath = Mutils.trim(opIdPath, "/").replace("/", "_");
            String opKey = method.httpMethod.name().toLowerCase();
            OperationObject existing = operations.get(opKey);
            if (existing == null) {
                existing = method.createOperationBuilder(customSchemas)
                    .withOperationId(method.httpMethod.name() + "_" + opPath)
                    .withTags(singletonList(root.tag.name()))
                    .withParameters(parameters)
                    .build();
            } else {
                OperationObject curOO = method.createOperationBuilder(customSchemas).build();
                List<ParameterObject> combinedParams = new ArrayList<>(existing.parameters());
                combinedParams.addAll(parameters);

                Map<String, MediaTypeObject> mergedContent = new HashMap<>();
                if (existing.requestBody() != null && existing.requestBody().content() != null) {
                    mergedContent.putAll(existing.requestBody().content());
                }
                if (curOO.requestBody() != null) {
                    mergedContent.putAll(curOO.requestBody().content());
                }
                OperationObjectBuilder operationObjectBuilder = OperationObjectBuilder.builderFrom(existing)
                    .withParameters(combinedParams)
                    .withResponses(mergeResponses(existing.responses(), curOO.responses()).build())
                    .withRequestBody(requestBodyObject()
                        .withRequired(existing.requestBody() != null && existing.requestBody().required() &&
                            curOO.requestBody() != null && curOO.requestBody().required())
                        .withDescription(Mutils.coalesce(existing.description(), curOO.description()))
                        .withContent(mergedContent)
                        .build());
                if (existing.summary() == null && existing.description() == null) {
                    operationObjectBuilder
                        .withSummary(curOO.summary())
                        .withDescription(curOO.description());
                }
                existing = operationObjectBuilder.build();
            }
            operations.put(opKey, existing);
        }
    }

    static String getPathWithoutRegex(ResourceClass rc, ResourceMethod rm, String parentResourcePath) {
        return "/" + Mutils.trim(Mutils.join(parentResourcePath, "/",
            Mutils.join(rc.pathPattern == null ? null : rc.pathPattern.pathWithoutRegex,
                "/", rm.pathPattern == null ? null : rm.pathPattern.pathWithoutRegex)), "/");
    }

}

class SchemaReference {
    final String id;
    final Class<?> type;
    final Type genericType;
    final SchemaObject schema;

    SchemaReference(String id, Class<?> type, Type genericType, SchemaObject schema) {
        this.id = id;
        this.type = type;
        this.genericType = genericType;
        this.schema = schema;
    }

    static SchemaReference find(List<SchemaReference> references, Class<?> type, Type genericType) {
        for (SchemaReference reference : references) {
            if (reference.type.equals(type)) {
                return reference;
            }
        }
        return null;
    }
}
