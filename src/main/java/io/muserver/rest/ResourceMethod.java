package io.muserver.rest;

import io.muserver.Method;
import io.muserver.openapi.OperationObjectBuilder;
import io.muserver.openapi.RequestBodyObject;
import io.muserver.openapi.RequestBodyObjectBuilder;
import io.muserver.openapi.ResponseObject;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.openapi.HeaderObjectBuilder.headerObject;
import static io.muserver.openapi.MediaTypeObjectBuilder.mediaTypeObject;
import static io.muserver.openapi.OperationObjectBuilder.operationObject;
import static io.muserver.openapi.RequestBodyObjectBuilder.requestBodyObject;
import static io.muserver.openapi.ResponseObjectBuilder.responseObject;
import static io.muserver.openapi.ResponsesObjectBuilder.responsesObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;
import static io.muserver.rest.MethodMapping.jaxToMu;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;

class ResourceMethod {
    final ResourceClass resourceClass;
    final UriPattern pathPattern;
    final java.lang.reflect.Method methodHandle;
    final Method httpMethod;
    final String pathTemplate;
    final List<MediaType> effectiveConsumes;
    final List<MediaType> directlyProduces;
    final List<MediaType> effectiveProduces;
    final List<ResourceMethodParam> params;
    final DescriptionData descriptionData;
    final boolean isDeprecated;

    ResourceMethod(ResourceClass resourceClass, UriPattern pathPattern, java.lang.reflect.Method methodHandle, List<ResourceMethodParam> params, Method httpMethod, String pathTemplate, List<MediaType> produces, List<MediaType> consumes, DescriptionData descriptionData, boolean isDeprecated) {
        this.resourceClass = resourceClass;
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.params = params;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.directlyProduces = produces;
        this.descriptionData = descriptionData;
        this.isDeprecated = isDeprecated;
        this.effectiveProduces = !produces.isEmpty() ? produces : (!resourceClass.produces.isEmpty() ? resourceClass.produces : RequestMatcher.WILDCARD_AS_LIST);
        this.effectiveConsumes = !consumes.isEmpty() ? consumes : (!resourceClass.consumes.isEmpty() ? resourceClass.consumes : RequestMatcher.WILDCARD_AS_LIST);
    }

    boolean isSubResource() {
        return pathPattern != null;
    }

    boolean isSubResourceLocator() {
        return httpMethod == null;
    }

    Object invoke(Object... params) throws Exception {
        try {
            return methodHandle.invoke(resourceClass.resourceInstance, params);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebApplicationException) {
                throw (WebApplicationException) cause;
            } else {
                throw e;
            }
        }
    }

    OperationObjectBuilder createOperationBuilder() {
        List<ApiResponse> apiResponseList = getApiResponses(methodHandle);


        Map<String, ResponseObject> httpStatusCodes = new HashMap<>();
        if (apiResponseList.isEmpty()) {
            httpStatusCodes.put("200", responseObject()
                .withDescription("Success")
                .withContent(effectiveProduces.stream().collect(toMap(MediaType::toString,
                    mt -> mediaTypeObject()
                        .build()))
                )
                .build());
        } else {
            for (ApiResponse apiResponse : apiResponseList) {
                httpStatusCodes.put(apiResponse.code(), responseObject()
                    .withContent(effectiveProduces.stream().collect(toMap(MediaType::toString,
                        mt -> mediaTypeObject()
                            .build()))
                    )
                    .withDescription(apiResponse.message())
                    .withHeaders(Stream.of(apiResponse.responseHeaders()).collect(
                        toMap(ResponseHeader::name,
                            rh -> headerObject().withDescription(rh.description()).withDeprecated(rh.deprecated()).withExample(rh.example()).build()
                        )))
                    .build());
            }
        }

        RequestBodyObject requestBody = params.stream()
            .filter(p -> p instanceof ResourceMethodParam.MessageBodyParam)
            .map(ResourceMethodParam.MessageBodyParam.class::cast)
            .map(messageBodyParam -> requestBodyObject()
                .withContent(singletonMap(effectiveConsumes.get(0).toString(), mediaTypeObject()
                    .build()))
                .withDescription(messageBodyParam.descriptionData.description)
                .build())
            .findFirst().orElse(null);

        if (requestBody == null) {
            List<ResourceMethodParam.RequestBasedParam> formParams = params.stream()
                .filter(p -> p instanceof ResourceMethodParam.RequestBasedParam)
                .map(ResourceMethodParam.RequestBasedParam.class::cast)
                .filter(p -> p.source == ResourceMethodParam.ValueSource.FORM_PARAM)
                .collect(Collectors.toList());
            if (!formParams.isEmpty()) {
                requestBody = requestBodyObject()
                    .withContent(Collections.singletonMap(effectiveConsumes.get(0).toString(),
                        mediaTypeObject()
                            .withSchema(
                                schemaObject()
                                    .withType("object")
                                    .withProperties(
                                        formParams.stream().collect(
                                            toMap(n -> n.key,
                                                n -> schemaObjectFrom(n.parameterHandle.getType())
                                                    .withDeprecated(n.isDeprecated)
                                                    .withDefaultValue(n.defaultValue())
                                                    .withDescription(n.descriptionData == null ? null : n.descriptionData.description).build()))
                                    )
                                .build()
                            )
                            .build()
                    ))
                    .build();
            }
        }

        return operationObject()
            .withSummary(descriptionData.summary)
            .withDescription(descriptionData.description)
            .withExternalDocs(descriptionData.externalDocumentation)
            .withDeprecated(isDeprecated)
            .withRequestBody(requestBody)
            .withResponses(
                responsesObject()
                    .withHttpStatusCodes(httpStatusCodes)
                    .build())
            ;
    }

    private static List<ApiResponse> getApiResponses(java.lang.reflect.Method methodHandle) {
        ApiResponses apiResponses = methodHandle.getDeclaredAnnotation(ApiResponses.class);
        List<ApiResponse> apiResponseList;
        if (apiResponses != null) {
            apiResponseList = new ArrayList<>(Arrays.asList(apiResponses.value()));
        } else {
            apiResponseList = new ArrayList<>();
        }
        ApiResponse apiResponse = methodHandle.getDeclaredAnnotation(ApiResponse.class);
        if (apiResponse != null) {
            apiResponseList.add(apiResponse);
        }
        return apiResponseList;
    }

    static Method getMuMethod(java.lang.reflect.Method restMethod) {
        Annotation[] annotations = restMethod.getAnnotations();
        Method value = null;
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> anno = annotation.annotationType();
            if (anno.getAnnotation(HttpMethod.class) != null) {
                if (value != null) {
                    throw new IllegalArgumentException("The method " + restMethod + " has multiple HttpMethod annotations. Only one is allowed per method.");
                }
                value = jaxToMu(anno);
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "ResourceMethod{" + resourceClass.resourceClassName() + "#" + methodHandle.getName() + "}";
    }

    boolean canProduceFor(List<MediaType> clientAccepts) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveProduces, clientAccepts);
    }

    boolean canConsume(MediaType requestBodyMediaType) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveConsumes, Collections.singletonList(requestBodyMediaType));
    }
}
