package io.muserver.rest;

import io.muserver.Method;
import io.muserver.Mutils;
import io.muserver.openapi.OperationObjectBuilder;
import io.muserver.openapi.RequestBodyObject;
import io.muserver.openapi.ResponseObject;
import io.muserver.openapi.SchemaObjectBuilder;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MediaType;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.Mutils.nullOrEmpty;
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
    private final DescriptionData descriptionData;
    private final boolean isDeprecated;
    private final List<Class<? extends Annotation>> nameBindingAnnotations;

    ResourceMethod(ResourceClass resourceClass, UriPattern pathPattern, java.lang.reflect.Method methodHandle, List<ResourceMethodParam> params, Method httpMethod, String pathTemplate, List<MediaType> produces, List<MediaType> consumes, DescriptionData descriptionData, boolean isDeprecated, List<Class<? extends Annotation>> nameBindingAnnotations) {
        this.resourceClass = resourceClass;
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.params = params;
        this.httpMethod = httpMethod;
        this.pathTemplate = pathTemplate;
        this.directlyProduces = produces;
        this.descriptionData = descriptionData;
        this.isDeprecated = isDeprecated;
        this.nameBindingAnnotations = nameBindingAnnotations;
        this.effectiveProduces = !produces.isEmpty() ? produces : (!resourceClass.produces.isEmpty() ? resourceClass.produces : RequestMatcher.WILDCARD_AS_LIST);
        this.effectiveConsumes = !consumes.isEmpty() ? consumes : (!resourceClass.consumes.isEmpty() ? resourceClass.consumes : RequestMatcher.WILDCARD_AS_LIST);
    }

    boolean hasAll(List<Class<? extends Annotation>> annotations) {
        for (Class<? extends Annotation> annotation : annotations) {
            if (!nameBindingAnnotations.contains(annotation) && !resourceClass.nameBindingAnnotations.contains(annotation)) {
                return false;
            }
        }
        return true;
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
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    OperationObjectBuilder createOperationBuilder() {
        List<ApiResponse> apiResponseList = getApiResponses(methodHandle);

        Map<String, ResponseObject> httpStatusCodes = new HashMap<>();
        if (apiResponseList.isEmpty()) {
            httpStatusCodes.put("200", responseObject()
                .withDescription("Success")
                .withContent(effectiveProduces.stream().collect(toMap(MediaType::toString,
                    mt -> mediaTypeObject().build()))
                )
                .build());
        } else {
            for (ApiResponse apiResponse : apiResponseList) {
                Stream<MediaType> responseTypes = apiResponse.contentType().length != 0 ?
                    Stream.of(apiResponse.contentType()).map(MediaType::valueOf)
                    : "204".equals(apiResponse.code()) ? null : effectiveProduces.stream();
                httpStatusCodes.put(apiResponse.code(), responseObject()
                    .withContent(responseTypes == null ? null : responseTypes.collect(toMap(MediaType::toString,
                        mt -> mediaTypeObject()
                            .withSchema(Void.class.equals(apiResponse.response()) ? null : schemaObjectFrom(apiResponse.response()).build())
                            .withExample(Mutils.nullOrEmpty(apiResponse.example()) ? null : apiResponse.example())
                            .build()))
                    )
                    .withDescription(apiResponse.message())
                    .withHeaders(
                        apiResponse.responseHeaders().length == 0 ? null :
                        Stream.of(apiResponse.responseHeaders()).collect(
                        toMap(ResponseHeader::name,
                            rh -> headerObject().withDescription(rh.description())
                                .withDeprecated(rh.deprecated() ? true : null)
                                .withExample(nullOrEmpty(rh.example()) ? null : rh.example()).build()
                        )))
                    .build());
            }
        }

        String requestBodyMimeType = effectiveConsumes.get(0).toString();
        RequestBodyObject requestBody = params.stream()
            .filter(p -> p instanceof ResourceMethodParam.MessageBodyParam)
            .map(ResourceMethodParam.MessageBodyParam.class::cast)
            .map(messageBodyParam -> requestBodyObject()
                .withContent(singletonMap(requestBodyMimeType,
                    mediaTypeObject()
                        .withSchema(SchemaObjectBuilder.schemaObjectFrom(messageBodyParam.parameterHandle.getType(), messageBodyParam.parameterHandle.getParameterizedType(), messageBodyParam.isRequired)
                            .withTitle(messageBodyParam.descriptionData.summary)
                            .withDescription(messageBodyParam.descriptionData.description)
                            .build())
                        .withExample(messageBodyParam.descriptionData.example)
                        .build()))
                .withDescription(messageBodyParam.descriptionData.summaryAndDescription())
                .withRequired(messageBodyParam.isRequired)
                .build())
            .findFirst().orElse(null);

        if (requestBody == null) {
            List<ResourceMethodParam.RequestBasedParam> formParams = params.stream()
                .filter(p -> p instanceof ResourceMethodParam.RequestBasedParam)
                .map(ResourceMethodParam.RequestBasedParam.class::cast)
                .filter(p -> p.source == ResourceMethodParam.ValueSource.FORM_PARAM)
                .collect(Collectors.toList());
            if (!formParams.isEmpty()) {
                List<String> required = new ArrayList<>();
                requestBody = requestBodyObject()
                    .withContent(singletonMap(requestBodyMimeType,
                        mediaTypeObject()
                            .withSchema(
                                schemaObject()
                                    .withType("object")
                                    .withRequired(required)
                                    .withProperties(
                                        formParams.stream().collect(
                                            toMap(n -> n.key,
                                                n -> {
                                                    if (n.isRequired) {
                                                        required.add(n.key);
                                                    }
                                                    SchemaObjectBuilder schemaObjectBuilder = schemaObjectFrom(n.parameterHandle.getType(), n.parameterHandle.getParameterizedType(), n.isRequired)
                                                        .withDeprecated(n.isDeprecated ? true : null)
                                                        .withDefaultValue(n.defaultValue());
                                                    if (n.descriptionData != null) {
                                                        String desc = n.descriptionData.summaryAndDescription();
                                                        schemaObjectBuilder.withExample(n.descriptionData.example)
                                                            .withDescription(n.key.equals(desc) ? null : desc);
                                                    }
                                                    return schemaObjectBuilder.build();
                                                }))
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
            .withDeprecated(isDeprecated ? true : null)
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
