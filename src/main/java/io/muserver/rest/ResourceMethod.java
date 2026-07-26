package io.muserver.rest;

import io.muserver.Method;
import io.muserver.openapi.*;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MediaType;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
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
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;

class ResourceMethod {
    final ResourceClass resourceClass;
    private final ResourceClassIntrospection.MethodInfo methodInfo;
    final List<MediaType> effectiveConsumes;
    final List<MediaType> effectiveProduces;
    final List<ResourceMethodParam> params;
    private final SchemaObjectCustomizer schemaObjectCustomizer;
    private final DescriptionData descriptionData;
    final Annotation[] methodAnnotations; // the annotations defined on the method to be passed to the message body writers

    ResourceMethod(ResourceClass resourceClass, ResourceClassIntrospection.MethodInfo methodInfo,
                   List<ResourceMethodParam> params, SchemaObjectCustomizer schemaObjectCustomizer,
                   DescriptionData descriptionData) {
        this.resourceClass = resourceClass;
        this.methodInfo = methodInfo;
        this.params = params;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.descriptionData = descriptionData;
        this.methodAnnotations = methodInfo.methodAnnotations.toArray(new Annotation[0]);
        this.effectiveProduces = !directlyProduces().isEmpty() ? directlyProduces() : (!resourceClass.produces.isEmpty() ? resourceClass.produces : RequestMatcher.WILDCARD_AS_LIST);
        this.effectiveConsumes = !directlyConsumes().isEmpty() ? directlyConsumes() : (!resourceClass.consumes.isEmpty() ? resourceClass.consumes : RequestMatcher.WILDCARD_AS_LIST);
    }

    @Nullable UriPattern pathPattern() {
        return methodInfo.pathPattern;
    }

    java.lang.reflect.Method methodHandle() {
        return methodInfo.methodHandle;
    }

    @Nullable Type genericReturnType() {
        return methodInfo.genericReturnType;
    }

    @Nullable Method httpMethod() {
        return methodInfo.httpMethod;
    }

    @Nullable String pathTemplate() {
        return methodInfo.pathTemplate;
    }

    List<MediaType> directlyConsumes() {
        return methodInfo.directlyConsumes;
    }

    List<MediaType> directlyProduces() {
        return methodInfo.directlyProduces;
    }

    boolean hasAll(List<Class<? extends Annotation>> annotations) {
        for (Class<? extends Annotation> annotation : annotations) {
            if (!methodInfo.nameBindingAnnotations.contains(annotation) && !resourceClass.nameBindingAnnotations.contains(annotation)) {
                return false;
            }
        }
        return true;
    }

    List<ResourceMethodParam> paramsIncludingLocators() {
        if (resourceClass.locatorMethod == null) {
            return params;
        }
        List<ResourceMethodParam> all = new ArrayList<>(resourceClass.locatorMethod.paramsIncludingLocators());
        all.addAll(params);
        return all;
    }

    boolean isSubResource() {
        return pathPattern() != null;
    }

    boolean isSubResourceLocator() {
        return httpMethod() == null;
    }

    UriPattern requiredPathPattern() {
        return Objects.requireNonNull(pathPattern(), "Resource method has no path pattern");
    }

    Method requiredHttpMethod() {
        return Objects.requireNonNull(httpMethod(), "Sub-resource locator has no HTTP method");
    }

    @Nullable Object invoke(@Nullable Object... params) throws Exception {
        try {
            return methodHandle().invoke(resourceClass.requiredResourceInstance(), params);
        } catch (InvocationTargetException e) {
            @Nullable Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }

    OperationObjectBuilder createOperationBuilder(List<SchemaReference> customSchemas) {
        List<ApiResponseObj> apiResponseList = getApiResponses(methodHandle());

        Map<String, ResponseObject> httpStatusCodes = new HashMap<>();

        for (ApiResponseObj apiResponse : apiResponseList) {
            Class<?> responseClass = apiResponse.response;

            @Nullable Stream<MediaType> responseTypesStream = apiResponse.contentType.length != 0 ?
                Stream.of(apiResponse.contentType).map(MediaType::valueOf)
                : "204".equals(apiResponse.code) ? null : effectiveProduces.stream();

            @Nullable Map<String, MediaTypeObject> content = responseTypesStream == null ? null : responseTypesStream.collect(toMap(MediaType::toString,
                mt -> {
                    @Nullable SchemaObject responseSchema;
                    @Nullable Object example = nullOrEmpty(apiResponse.example) ? null : apiResponse.example;

                    if (void.class.isAssignableFrom(responseClass)) {
                        responseSchema = null;
                    } else {
                        SchemaReference schemaReference = SchemaReference.find(customSchemas, responseClass, apiResponse.genericReturnType);
                        SchemaObjectBuilder builder = schemaReference == null ? schemaObjectFrom(responseClass) : schemaReference.schema.toBuilder();
                        responseSchema = schemaObjectCustomizer.customize(builder,
                            schemaContext(SchemaObjectCustomizerTarget.RESPONSE_BODY, null, responseClass, apiResponse.genericReturnType, mt)).build();
                        if (responseSchema.example() != null) {
                            example = responseSchema.example();
                        }
                    }
                    return mediaTypeObject()
                        .withSchema(responseSchema)
                        .withExample(example)
                        .build();
                }));
            httpStatusCodes.put(apiResponse.code, responseObject()
                .withContent(content)
                .withDescription(apiResponse.message)
                .withHeaders(
                    apiResponse.responseHeaders.length == 0 ? null :
                        Stream.of(apiResponse.responseHeaders).collect(
                            toMap(ResponseHeader::name,
                                rh -> headerObject().withDescription(rh.description())
                                    .withDeprecated(rh.deprecated() ? true : null)
                                    .withExample(nullOrEmpty(rh.example()) ? null : rh.example()).build()
                            )))
                .build());
        }

        MediaType requestBodyMediaType = effectiveConsumes.get(0);
        String requestBodyMimeType = requestBodyMediaType.toString();
        @Nullable RequestBodyObject requestBody = params.stream()
            .filter(p -> p instanceof ResourceMethodParam.MessageBodyParam)
            .map(ResourceMethodParam.MessageBodyParam.class::cast)
            .map(messageBodyParam -> {
                Class<?> bodyType = messageBodyParam.type();
                Type bodyParameterizedType = messageBodyParam.genericType();
                SchemaReference schemaReference = SchemaReference.find(customSchemas, bodyType, bodyParameterizedType);
                SchemaObjectBuilder builder = schemaReference != null ? schemaReference.schema.toBuilder() :
                    schemaObjectFrom(bodyType, bodyParameterizedType, messageBodyParam.isRequired())
                        .withTitle(Objects.requireNonNull(messageBodyParam.descriptionData).summary)
                        .withDescription(messageBodyParam.descriptionData.description);
                return requestBodyObject()
                    .withContent(singletonMap(requestBodyMimeType,
                        mediaTypeObject()
                            .withSchema(
                                schemaObjectCustomizer.customize(builder,
                                    schemaContext(SchemaObjectCustomizerTarget.REQUEST_BODY, null, bodyType, bodyParameterizedType, requestBodyMediaType))
                                    .build())
                            .withExample(Objects.requireNonNull(messageBodyParam.descriptionData).example)
                            .build()))
                    .withDescription(messageBodyParam.descriptionData.summaryAndDescription())
                    .withRequired(messageBodyParam.isRequired())
                    .build();
            })
            .findFirst().orElse(null);

        if (requestBody == null) {
            List<ResourceMethodParam.RequestBasedParam> formParams = params.stream()
                .filter(p -> p instanceof ResourceMethodParam.RequestBasedParam)
                .map(ResourceMethodParam.RequestBasedParam.class::cast)
                .filter(p -> p.source() == ResourceMethodParam.ValueSource.FORM_PARAM)
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
                                            toMap(ResourceMethodParam.RequestBasedParam::key,
                                                n -> {
                                                    if (n.isRequired()) {
                                                        required.add(n.key());
                                                    }
                                                    Class<?> paramType = n.type();
                                                    Type paramParameterizedType = n.genericType();

                                                    SchemaReference schemaReference = SchemaReference.find(customSchemas, paramType, paramParameterizedType);
                                                    SchemaObjectBuilder schemaObjectBuilder = schemaReference != null ? schemaReference.schema.toBuilder() : schemaObjectFrom(paramType, paramParameterizedType, n.isRequired());
                                                    schemaObjectBuilder.withDeprecated(n.deprecated() ? true : null);
                                                    if (n.hasExplicitDefault()) {
                                                        schemaObjectBuilder.withDefaultValue(n.defaultValue());
                                                    }
                                                    if (n.descriptionData != null) {
                                                        String desc = n.descriptionData.summaryAndDescription();
                                                        schemaObjectBuilder.withExample(n.descriptionData.example)
                                                            .withDescription(n.key().equals(desc) ? null : desc);
                                                    }
                                                    return schemaObjectCustomizer
                                                        .customize(schemaObjectBuilder, schemaContext(SchemaObjectCustomizerTarget.FORM_PARAM, n.key(), paramType, paramParameterizedType, requestBodyMediaType))
                                                        .build();
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
            .withDeprecated(methodInfo.deprecated ? true : null)
            .withRequestBody(requestBody)
            .withResponses(
                responsesObject()
                    .withHttpStatusCodes(httpStatusCodes)
                    .build())
            ;
    }

    private SchemaObjectCustomizerContext schemaContext(SchemaObjectCustomizerTarget target, @Nullable String parameter, Class<?> type, @Nullable Type parameterizedType, MediaType mediaType) {
        return new SchemaObjectCustomizerContext(target, type, parameterizedType, resourceClass.resourceInstance, methodHandle(), parameter, mediaType);
    }

    private static List<ApiResponseObj> getApiResponses(java.lang.reflect.Method methodHandle) {
        ApiResponses apiResponses = methodHandle.getDeclaredAnnotation(ApiResponses.class);
        List<ApiResponseObj> result = new ArrayList<>();
        if (apiResponses != null) {
            for (ApiResponse ar : apiResponses.value()) {
                result.add(ApiResponseObj.fromAnnotation(ar, methodHandle));
            }
        }
        ApiResponse single = methodHandle.getDeclaredAnnotation(ApiResponse.class);
        if (single != null) {
            result.add(ApiResponseObj.fromAnnotation(single, methodHandle));
        }
        if (result.isEmpty()) {
            Class<?> returnType = methodHandle.getReturnType();
            Type genericReturnType = methodHandle.getGenericReturnType();
            String code = void.class.isAssignableFrom(returnType) ? "204" : "200";
            result.add(new ApiResponseObj(code, "Success", new ResponseHeader[0], returnType, genericReturnType, new String[0], null));
        }
        return result;
    }

    static @Nullable Method getMuMethod(java.lang.reflect.Method restMethod) {
        Annotation[] annotations = restMethod.getAnnotations();
        @Nullable Method value = null;
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> anno = annotation.annotationType();
            HttpMethod httpMethodAnno = anno.getAnnotation(HttpMethod.class);
            if (httpMethodAnno != null) {
                if (value != null) {
                    throw new IllegalArgumentException("The method " + restMethod + " has multiple HttpMethod annotations. Only one is allowed per method.");
                }
                value = Method.valueOf(httpMethodAnno.value());
            }
        }
        return value;
    }

    @Override
    public String toString() {
        return "ResourceMethod{" + resourceClass.resourceClassName() + "#" + methodHandle().getName() + "}";
    }

    boolean canProduceFor(List<MediaType> clientAccepts) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveProduces, clientAccepts, null);
    }

    boolean canConsume(MediaType requestBodyMediaType) {
        return MediaTypeHeaderDelegate.atLeastOneCompatible(effectiveConsumes, Collections.singletonList(requestBodyMediaType), null);
    }
}
