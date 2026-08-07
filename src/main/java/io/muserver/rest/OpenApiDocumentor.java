package io.muserver.rest;

import io.muserver.*;
import io.muserver.openapi.*;
import jakarta.ws.rs.ext.ParamConverterProvider;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.ComponentsObjectBuilder.componentsObject;
import static io.muserver.openapi.PathItemObjectBuilder.pathItemObject;
import static io.muserver.openapi.PathsObjectBuilder.pathsObject;
import static io.muserver.openapi.RequestBodyObjectBuilder.requestBodyObject;
import static io.muserver.openapi.ResponsesObjectBuilder.mergeResponses;
import static io.muserver.openapi.ServerObjectBuilder.serverObject;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;

class OpenApiDocumentor implements MuHandler {
    private static final Pattern PATH_TEMPLATE_PATTERN = Pattern.compile("\\{([^{}]+)}");
    private final List<ResourceClass> roots;
    private final @Nullable String openApiJsonUrl;
    private final OpenAPIObject openAPIObject;
    private final @Nullable String openApiHtmlUrl;
    private final @Nullable String openApiHtmlCss;
    private final CORSConfig corsConfig;
    private final List<SchemaReference> customSchemas;
    private final SchemaObjectCustomizer schemaObjectCustomizer;
    private final List<ParamConverterProvider> paramConverterProviders;

    OpenApiDocumentor(List<ResourceClass> roots, @Nullable String openApiJsonUrl, @Nullable String openApiHtmlUrl, OpenAPIObject openAPIObject, @Nullable String openApiHtmlCss, CORSConfig corsConfig, List<SchemaReference> customSchemas, SchemaObjectCustomizer schemaObjectCustomizer, List<ParamConverterProvider> paramConverterProviders) {
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

        Map<String, PathItemObjectBuilder> pathItemBuilders = new LinkedHashMap<>();
        for (ResourceClass root : roots) {
            addResourceClass(0, Collections.emptyList(), tags, pathItemBuilders, root);
        }
        Map<String, PathItemObject> pathItems = pathItemBuilders.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, v -> v.getValue().build()));


        ComponentsObject components = openAPIObject.components();
        if (!customSchemas.isEmpty()) {
            ComponentsObjectBuilder componentsObjectBuilder = componentsObject(components);
            Map<String, SchemaObject> schemas = (components != null && components.schemas() != null) ? components.schemas() : new HashMap<>();
            for (SchemaReference customSchema : customSchemas) {
                if (!schemas.containsKey(customSchema.id)) {
                    schemas.put(customSchema.id, customSchema.schema);
                }
            }
            components = componentsObjectBuilder.withSchemas(schemas).build();
        }

        OpenAPIObjectBuilder api = OpenAPIObjectBuilder.openAPIObject()
            .withInfo(openAPIObject.info())
            .withExternalDocs(openAPIObject.externalDocs())
            .withSecurity(openAPIObject.security())
            .withComponents(components)
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
                new HtmlDocumentor(writer, builtApi,
                    Objects.requireNonNull(openApiHtmlCss, "OpenAPI HTML CSS was not initialized"),
                    request.uri()).writeHtml();
            }
        }

        return true;
    }

    private void addResourceClass(int recursiveLevel, List<PathPart> parentPathParts, List<TagObject> tags, Map<String, PathItemObjectBuilder> pathItems, ResourceClass root) {
        if (recursiveLevel == 5) {
            return;
        }
        if (!tags.contains(root.tag)) {
            tags.add(root.tag);
        }

        for (ResourceMethod method : root.resourceMethods) {
            if (method.isSubResourceLocator()) {
                ResourceClass rc = ResourceClass.forSubResourceLocator(method, method.methodHandle().getReturnType(), null, schemaObjectCustomizer, paramConverterProviders);
                List<PathPart> newParentPathParts = new ArrayList<>(parentPathParts);
                newParentPathParts.add(resourcePathPart(root));
                addResourceClass(recursiveLevel + 1, newParentPathParts, tags, pathItems, rc);
                continue;
            }

            DocumentedPath documentedPath = documentedPath(parentPathParts, root, method);
            String path = documentedPath.path;

            Map<String, OperationObject> operations;
            if (pathItems.containsKey(path)) {
                PathItemObjectBuilder pathItem = Objects.requireNonNull(pathItems.get(path));
                Map<String, OperationObject> configuredOperations = pathItem.operations();
                operations = configuredOperations == null ? new LinkedHashMap<>() : configuredOperations;
                if (configuredOperations == null) {
                    pathItem.withOperations(operations);
                }
            } else {
                operations = new LinkedHashMap<>();
                PathItemObjectBuilder pathItem = pathItemObject()
                    .withOperations(operations);
                pathItems.put(path, pathItem);
            }
            List<ParameterObject> parameters = method.paramsIncludingLocators().stream()
                .filter(p -> p instanceof ResourceMethodParam.RequestBasedParam)
                .map(ResourceMethodParam.RequestBasedParam.class::cast)
                .filter(p -> p.source().openAPIIn != null || documentedPath.matrixParams.containsKey(p))
                .map(p -> {
                    MatrixParamDocumentation matrixParam = documentedPath.matrixParams.get(p);
                    ParameterObjectBuilder builder = p.createDocumentationBuilder(matrixParam == null ? p.key() : matrixParam.parameterName);
                    if (matrixParam != null && matrixParam.nativeMatrixStyle) {
                        builder.withStyle("matrix").withExplode(true);
                    }
                    return builder.build();
                })
                .reduce(new ArrayList<>(), (parameterObjects, parameterObject) -> {
                    if (parameterObjects.stream().noneMatch(existing -> existing.name().equals(parameterObject.name()) && existing.in().equals(parameterObject.in())))
                    parameterObjects.add(parameterObject);
                    return parameterObjects;
                }, (list1, list2) -> {
                    list1.addAll(list2);
                    return list1;
                });

            String opIdPath = path.replace("{", "_").replace("}", "_");
            String opPath = Mutils.trim(opIdPath, "/").replace("/", "_");
            String opKey = method.requiredHttpMethod().name().toLowerCase(Locale.ROOT);
            OperationObject existing = operations.get(opKey);
            if (existing == null) {
                existing = method.createOperationBuilder(customSchemas)
                    .withOperationId(method.requiredHttpMethod().name() + "_" + opPath)
                    .withTags(singletonList(root.tag.name()))
                    .withParameters(parameters)
                    .build();
            } else {
                OperationObject curOO = method.createOperationBuilder(customSchemas).build();
                List<ParameterObject> combinedParams = new ArrayList<>(
                    existing.parameters() == null ? Collections.emptyList() : existing.parameters());
                for (ParameterObject po : parameters) {
                    // add to combinedParams if none with same name and in
                    if (combinedParams.stream().noneMatch(p -> p.name().equals(po.name()) &&
                        p.in().equals(po.in()))) {
                        combinedParams.add(po);
                    }
                }

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

    private static DocumentedPath documentedPath(List<PathPart> parentPathParts, ResourceClass resourceClass, ResourceMethod resourceMethod) {
        List<PathPart> parts = new ArrayList<>(parentPathParts);
        parts.add(resourcePathPart(resourceClass));
        parts.add(new PathPart(resourceMethod.pathPattern() == null ? null : resourceMethod.pathPattern().pathWithoutRegex,
            matrixParams(resourceMethod.params)));

        Map<String, Integer> matrixParamSiteCounts = new HashMap<>();
        for (PathPart part : parts) {
            for (String wireName : paramsByWireName(part.matrixParams).keySet()) {
                matrixParamSiteCounts.merge(wireName, 1, Integer::sum);
            }
        }

        Set<String> usedPathParameterNames = resourceMethod.paramsIncludingLocators().stream()
            .filter(p -> p.source() == ResourceMethodParam.ValueSource.PATH_PARAM)
            .map(ResourceMethodParam.RequestBasedParam.class::cast)
            .map(ResourceMethodParam.RequestBasedParam::key)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (PathPart part : parts) {
            if (part.path != null) {
                Matcher matcher = PATH_TEMPLATE_PATTERN.matcher(part.path);
                while (matcher.find()) {
                    usedPathParameterNames.add(matcher.group(1));
                }
            }
        }
        IdentityHashMap<ResourceMethodParam.RequestBasedParam, MatrixParamDocumentation> documentedMatrixParams = new IdentityHashMap<>();

        String plainPath = "";
        String decoratedPath = "";
        for (PathPart part : parts) {
            plainPath = Mutils.join(plainPath, "/", part.path);
            decoratedPath = Mutils.join(decoratedPath, "/", part.path);
            for (Map.Entry<String, List<ResourceMethodParam.RequestBasedParam>> entry : paramsByWireName(part.matrixParams).entrySet()) {
                String wireName = entry.getKey();
                List<ResourceMethodParam.RequestBasedParam> params = entry.getValue();
                boolean multiValued = params.stream().anyMatch(ResourceMethodParam.RequestBasedParam::isMultiValued);
                boolean needsAlias = Objects.requireNonNull(matrixParamSiteCounts.get(wireName)) > 1 || usedPathParameterNames.contains(wireName);

                // Matrix-style arrays use the OpenAPI parameter name as their wire name. If that name
                // needs an alias, OpenAPI cannot express the repeated values without changing the wire URI.
                if (multiValued && needsAlias) {
                    continue;
                }

                String parameterName = needsAlias
                    ? uniqueParameterName(matrixAliasBase(plainPath, wireName), usedPathParameterNames)
                    : wireName;
                usedPathParameterNames.add(parameterName);
                ResourceMethodParam.RequestBasedParam representative = params.stream()
                    .filter(ResourceMethodParam.RequestBasedParam::isMultiValued)
                    .findFirst()
                    .orElse(params.get(0));
                MatrixParamDocumentation documentation = new MatrixParamDocumentation(parameterName, multiValued);
                documentedMatrixParams.put(representative, documentation);

                if (multiValued) {
                    String template = "{" + parameterName + "}";
                    if (!decoratedPath.endsWith(template)) {
                        decoratedPath += template;
                    }
                } else {
                    String template = ";" + wireName + "={" + parameterName + "}";
                    if (!decoratedPath.contains(template)) {
                        decoratedPath += template;
                    }
                }
            }
        }
        return new DocumentedPath("/" + Mutils.trim(decoratedPath, "/"), documentedMatrixParams);
    }

    private static PathPart resourcePathPart(ResourceClass resourceClass) {
        return new PathPart(resourceClass.pathPattern.pathWithoutRegex,
            resourceClass.locatorMethod == null ? Collections.emptyList() : matrixParams(resourceClass.locatorMethod.params));
    }

    private static List<ResourceMethodParam.RequestBasedParam> matrixParams(List<ResourceMethodParam> params) {
        return params.stream()
            .filter(p -> p.source() == ResourceMethodParam.ValueSource.MATRIX_PARAM)
            .map(ResourceMethodParam.RequestBasedParam.class::cast)
            .collect(Collectors.toList());
    }

    private static Map<String, List<ResourceMethodParam.RequestBasedParam>> paramsByWireName(List<ResourceMethodParam.RequestBasedParam> params) {
        Map<String, List<ResourceMethodParam.RequestBasedParam>> byName = new LinkedHashMap<>();
        for (ResourceMethodParam.RequestBasedParam param : params) {
            byName.computeIfAbsent(param.key(), ignored -> new ArrayList<>()).add(param);
        }
        return byName;
    }

    private static String matrixAliasBase(String path, String wireName) {
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        Matcher matcher = PATH_TEMPLATE_PATTERN.matcher(lastSegment);
        @Nullable String segmentName = null;
        while (matcher.find()) {
            segmentName = matcher.group(1);
        }
        if (segmentName == null) {
            segmentName = lastSegment;
        }
        String safeSegmentName = safeParameterName(segmentName);
        return (safeSegmentName.isEmpty() ? "matrix" : safeSegmentName) + "_" + safeParameterName(wireName);
    }

    private static String safeParameterName(String name) {
        String safeName = name.replaceAll("[^A-Za-z0-9_]", "_");
        return !safeName.isEmpty() && Character.isDigit(safeName.charAt(0)) ? "_" + safeName : safeName;
    }

    private static String uniqueParameterName(String base, Set<String> usedNames) {
        String candidate = base;
        for (int suffix = 2; usedNames.contains(candidate); suffix++) {
            candidate = base + "_" + suffix;
        }
        return candidate;
    }

    private static final class PathPart {
        private final @Nullable String path;
        private final List<ResourceMethodParam.RequestBasedParam> matrixParams;

        private PathPart(@Nullable String path, List<ResourceMethodParam.RequestBasedParam> matrixParams) {
            this.path = path;
            this.matrixParams = matrixParams;
        }
    }

    private static final class MatrixParamDocumentation {
        private final String parameterName;
        private final boolean nativeMatrixStyle;

        private MatrixParamDocumentation(String parameterName, boolean nativeMatrixStyle) {
            this.parameterName = parameterName;
            this.nativeMatrixStyle = nativeMatrixStyle;
        }
    }

    private static final class DocumentedPath {
        private final String path;
        private final IdentityHashMap<ResourceMethodParam.RequestBasedParam, MatrixParamDocumentation> matrixParams;

        private DocumentedPath(String path, IdentityHashMap<ResourceMethodParam.RequestBasedParam, MatrixParamDocumentation> matrixParams) {
            this.path = path;
            this.matrixParams = matrixParams;
        }
    }

}

class SchemaReference {
    final String id;
    final Class<?> type;
    final @Nullable Type genericType;
    final SchemaObject schema;

    SchemaReference(String id, Class<?> type, @Nullable Type genericType, SchemaObject schema) {
        this.id = id;
        this.type = type;
        this.genericType = genericType;
        this.schema = schema;
    }

    static @Nullable SchemaReference find(List<SchemaReference> references, Class<?> type, @Nullable Type genericType) {
        for (SchemaReference reference : references) {
            if (reference.type.equals(type)) {
                return reference;
            }
        }
        return null;
    }
}
