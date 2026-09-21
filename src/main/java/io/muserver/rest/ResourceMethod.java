package io.muserver.rest;

import io.muserver.Method;
import io.muserver.openapi.*;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Providers;
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
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;

class ResourceMethod {
    final ResourceClass resourceClass;
    private final ResourceClassIntrospection.MethodInfo methodInfo;
    private final List<MediaType> directlyConsumes;
    private final List<MediaType> directlyProduces;
    final List<MediaType> effectiveConsumes;
    final List<MediaType> effectiveProduces;
    final List<ResourceMethodParam> params;
    private final SchemaObjectCustomizer schemaObjectCustomizer;
    private final DescriptionData descriptionData;
    final Annotation[] methodAnnotations; // the annotations defined on the method to be passed to the message body writers

    ResourceMethod(ResourceClass resourceClass, ResourceClassIntrospection.MethodInfo methodInfo,
                   List<ResourceMethodParam> params, SchemaObjectCustomizer schemaObjectCustomizer) {
        this.resourceClass = resourceClass;
        this.methodInfo = methodInfo;
        this.params = params;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.descriptionData = methodInfo.descriptionData;
        this.methodAnnotations = methodInfo.methodAnnotations.toArray(new Annotation[0]);
        this.directlyProduces = Collections.unmodifiableList(
            new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(methodInfo.directlyProduces)));
        this.directlyConsumes = Collections.unmodifiableList(
            new ArrayList<>(MediaTypeHeaderDelegate.fromStrings(methodInfo.directlyConsumes)));
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
        return directlyConsumes;
    }

    List<MediaType> directlyProduces() {
        return directlyProduces;
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

    OperationObjectBuilder createOperationBuilder(List<SchemaReference> customSchemas, Providers providers) {
        Map<String, ResponseObject> httpStatusCodes = new TreeMap<>();
        for (ApiResponseObj apiResponse : getApiResponses()) {
            Type responseType = unwrap(apiResponse.genericReturnType == null ? apiResponse.response : apiResponse.genericReturnType);
            Class<?> responseClass = SchemaReference.rawClass(responseType);
            if (responseClass == null) responseClass = Object.class;
            Map<String, MediaTypeObject> content = new LinkedHashMap<>();
            boolean bodyless = requiredHttpMethod() == Method.HEAD || apiResponse.code.matches("1(?:[0-9]{2}|XX)|204|205|304")
                || responseClass == void.class || responseClass == Void.class;
            if (!bodyless) {
                List<MediaType> mediaTypes = apiResponse.contentType.length == 0 ? effectiveProduces :
                    Stream.of(apiResponse.contentType).map(MediaType::valueOf).collect(Collectors.toList());
                for (MediaType mediaType : mediaTypes) {
                    SchemaObject responseSchema = documentedSchema(customSchemas, responseClass, responseType,
                        SchemaObjectCustomizerTarget.RESPONSE_BODY, null, mediaType, null, null, providers);
                    content.put(mediaType.toString(), mediaTypeObject().withSchema(responseSchema)
                        .withExample(nullOrEmpty(apiResponse.example) ? responseSchema.example() : apiResponse.example).build());
                }
            }
            Map<String, HeaderObject> headers = new LinkedHashMap<>();
            for (ResponseHeader header : apiResponse.responseHeaders) {
                headers.put(header.name(), headerObject().withDescription(header.description())
                    .withSchema(schemaObject().withType("string").build())
                    .withDeprecated(header.deprecated() ? true : null)
                    .withExample(nullOrEmpty(header.example()) ? null : header.example()).build());
            }
            ResponseObject response = responseObject().withDescription(apiResponse.message)
                .withHeaders(headers.isEmpty() ? null : headers).withContent(content.isEmpty() ? null : content).build();
            httpStatusCodes.merge(apiResponse.code, response, (left, right) -> ResponseObjectBuilder.mergeResponses(left, right).build());
        }

        addSseResponses(httpStatusCodes, customSchemas);
        RequestBodyObject requestBody = null;
        for (ResourceMethodParam param : params) {
            if (!(param instanceof ResourceMethodParam.MessageBodyParam)) continue;
            ResourceMethodParam.MessageBodyParam body = (ResourceMethodParam.MessageBodyParam) param;
            Map<String, MediaTypeObject> content = new LinkedHashMap<>();
            for (MediaType mediaType : effectiveConsumes) {
                SchemaObject schema = documentedSchema(customSchemas, body.type(), body.genericType(),
                    SchemaObjectCustomizerTarget.REQUEST_BODY, null, mediaType, null, body, providers);
                content.put(mediaType.toString(), mediaTypeObject().withSchema(schema)
                    .withExample(body.descriptionData == null ? null : body.descriptionData.example).build());
            }
            requestBody = requestBodyObject().withContent(content).withRequired(body.isRequired())
                .withDescription(body.descriptionData == null ? null : body.descriptionData.summaryAndDescription()).build();
            break;
        }
        if (requestBody == null) {
            List<ResourceMethodParam.RequestBasedParam> forms = params.stream()
                .filter(p -> p instanceof ResourceMethodParam.RequestBasedParam && p.source() == ResourceMethodParam.ValueSource.FORM_PARAM)
                .map(ResourceMethodParam.RequestBasedParam.class::cast).collect(Collectors.toList());
            if (!forms.isEmpty()) {
                Map<String, MediaTypeObject> content = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();
                for (ResourceMethodParam.RequestBasedParam form : forms) if (form.isRequired()) required.add(form.key());
                for (MediaType mediaType : effectiveConsumes) {
                    Map<String, SchemaObject> properties = new LinkedHashMap<>();
                    Map<String, EncodingObject> encoding = new LinkedHashMap<>();
                    for (ResourceMethodParam.RequestBasedParam form : forms) {
                        properties.put(form.key(), documentedSchema(customSchemas, form.type(), form.genericType(),
                            SchemaObjectCustomizerTarget.FORM_PARAM, form.key(), mediaType, form, null, providers));
                        boolean urlEncoded = mediaType.isCompatible(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
                        Class<?> formType = form.type().isArray() ? form.type().getComponentType() : form.type();
                        if (io.muserver.UploadedFile.class.isAssignableFrom(formType) || java.io.File.class.isAssignableFrom(form.type())) {
                            encoding.put(form.key(), EncodingObjectBuilder.encodingObject().withContentType("application/octet-stream").build());
                        } else if (form.isMultiValued() && urlEncoded) {
                            encoding.put(form.key(), EncodingObjectBuilder.encodingObject().withStyle("form").withExplode(true).build());
                        }
                    }
                    content.put(mediaType.toString(), mediaTypeObject().withSchema(schemaObject().withType("object")
                        .withProperties(properties).withRequired(required.isEmpty() ? null : required).build())
                        .withEncoding(encoding.isEmpty() ? null : encoding).build());
                }
                requestBody = requestBodyObject().withContent(content).withRequired(!required.isEmpty()).build();
            }
        }
        ResponseObject defaultResponse = httpStatusCodes.remove("default");
        return operationObject().withSummary(descriptionData.summary).withDescription(descriptionData.description)
            .withExternalDocs(descriptionData.externalDocumentation).withDeprecated(methodInfo.deprecated ? true : null)
            .withRequestBody(requestBody).withResponses(responsesObject().withHttpStatusCodes(httpStatusCodes)
                .withDefaultValue(defaultResponse).build());
    }

    SchemaObject parameterSchema(List<SchemaReference> registrations, ResourceMethodParam.RequestBasedParam parameter, String documentationName) {
        if (!params.contains(parameter)) {
            ResourceMethod locator = resourceClass.locatorMethod;
            if (locator == null) throw new IllegalArgumentException("Parameter does not belong to this resource method or its locators");
            return locator.parameterSchema(registrations, parameter, documentationName);
        }
        return documentedSchema(registrations, parameter.type(), parameter.genericType(),
            SchemaObjectCustomizerTarget.PARAMETER, documentationName, MediaType.TEXT_PLAIN_TYPE, parameter, null, null);
    }

    private SchemaObject documentedSchema(List<SchemaReference> registrations, Class<?> type, @Nullable Type genericType,
                                          SchemaObjectCustomizerTarget target, @Nullable String name, MediaType mediaType,
                                          ResourceMethodParam.@Nullable RequestBasedParam form, ResourceMethodParam.@Nullable MessageBodyParam body, @Nullable Providers providers) {
        SchemaReference registration = SchemaReference.find(registrations, type, genericType);
        SchemaObjectBuilder builder = registration != null ? registration.schema.toBuilder() : form != null ? form.documentationSchema(registrations) : entitySchema(registrations, type, genericType, target, mediaType, body == null ? methodAnnotations : body.annotations, providers);
        DescriptionData bodyDescription = body == null ? null : body.descriptionData;
        if (bodyDescription != null) {
            if (bodyDescription.summary != null) builder.withTitle(bodyDescription.summary);
            if (bodyDescription.description != null) builder.withDescription(bodyDescription.description);
            if (bodyDescription.example != null) builder.withExamples(Collections.singletonList(bodyDescription.example));
        }
        if (form != null) {
            SchemaObject documented = form.source().openAPIIn == null ? null : form.createDocumentationBuilder().build().schema();
            if (documented != null) {
                if (documented.pattern() != null) builder.withPattern(documented.pattern());
                if (documented.externalDocs() != null) builder.withExternalDocs(documented.externalDocs());
            }
            if (form.deprecated()) builder.withDeprecated(true);
            if (form.hasExplicitDefault()) builder.withDefaultValue(form.documentationDefaultValue());
            if (form.descriptionData != null) {
                String description = form.descriptionData.summaryAndDescription();
                if (!form.key().equals(description)) builder.withDescription(description);
                if (form.descriptionData.example != null) builder.withExamples(Collections.singletonList(form.descriptionData.example));
            }
        }
        SchemaObject schema = schemaObjectCustomizer.customize(builder, new SchemaObjectCustomizerContext(target, type, genericType, resourceClass.resourceInstance,
            methodHandle(), name, mediaType, form == null ? null : form.source().parameterLocation)).build();
        return registration != null && schema.toString().equals(registration.schema.toString())
            ? schemaObject().withRef("#/components/schemas/" + registration.id).build() : schema;
    }

    private SchemaObjectBuilder entitySchema(List<SchemaReference> registrations, Class<?> type, @Nullable Type genericType,
                                              SchemaObjectCustomizerTarget target, MediaType mediaType, Annotation[] annotations,
                                              @Nullable Providers providers) {
        if (providers == null) return schemaObject();
        Type resolved = genericType == null ? type : genericType;
        Object provider;
        boolean builtIn;
        if (target == SchemaObjectCustomizerTarget.REQUEST_BODY) {
            jakarta.ws.rs.ext.MessageBodyReader<?> reader = providers.getMessageBodyReader(type, resolved, annotations, mediaType);
            provider = reader;
            builtIn = reader != null && providers instanceof JaxRSProviders && ((JaxRSProviders) providers).isBuiltInReader(reader);
        } else {
            jakarta.ws.rs.ext.MessageBodyWriter<?> writer = providers.getMessageBodyWriter(type, resolved, methodAnnotations, mediaType);
            provider = writer;
            builtIn = writer != null && providers instanceof JaxRSProviders && ((JaxRSProviders) providers).isBuiltInWriter(writer);
        }
        if (provider == null) return SchemaReference.infer(registrations, type, genericType);
        if (!builtIn) return schemaObject();
        if (provider instanceof StringEntityProviders.ReaderEntityReader || provider instanceof StringEntityProviders.ReaderEntityWriter
            || provider instanceof StringEntityProviders.CharArrayReaderWriter) return schemaObject().withType("string");
        if (provider instanceof StringEntityProviders.TemporalEntityReaderWriter) {
            return target == SchemaObjectCustomizerTarget.REQUEST_BODY
                ? JavaValueSchemas.temporalInput(type) : JavaValueSchemas.temporalOutput(type);
        }
        return SchemaReference.infer(registrations, type, genericType);
    }

    private boolean isSse() {
        return effectiveProduces.stream().anyMatch(m -> "text".equals(m.getType()) && "event-stream".equals(m.getSubtype()))
            || params.stream().anyMatch(p -> jakarta.ws.rs.sse.SseEventSink.class.isAssignableFrom(p.type())
                || jakarta.ws.rs.sse.Sse.class.isAssignableFrom(p.type())) || !sseDeclarations().isEmpty();
    }

    private Map<String, ApiSseEvent> sseDeclarations() {
        Map<String, ApiSseEvent> result = new LinkedHashMap<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = resourceClass.resourceClass; c != null && c != Object.class; c = c.getSuperclass()) hierarchy.add(c);
        Collections.reverse(hierarchy);
        for (Class<?> c : hierarchy) mergeSseDeclarations(result, c.getDeclaredAnnotationsByType(ApiSseEvent.class));
        List<ApiSseEvent> method = new ArrayList<>();
        for (Annotation annotation : methodAnnotations) {
            if (annotation instanceof ApiSseEvent) method.add((ApiSseEvent) annotation);
            if (annotation instanceof ApiSseEvents) Collections.addAll(method, ((ApiSseEvents) annotation).value());
        }
        mergeSseDeclarations(result, method.toArray(new ApiSseEvent[0]));
        return result;
    }

    private static void mergeSseDeclarations(Map<String, ApiSseEvent> result, ApiSseEvent[] declarations) {
        Set<String> level = new HashSet<>();
        for (ApiSseEvent declaration : declarations) {
            String key = declaration.code() + "\0" + declaration.name();
            if (!level.add(key)) throw new IllegalArgumentException("Duplicate SSE declaration for " + declaration.code() + "/" + declaration.name());
            if (!declaration.code().matches("[1-5][0-9]{2}|[1-5]XX|default")) throw new IllegalArgumentException("Invalid SSE response code");
            MediaType.valueOf(declaration.mediaType());
            result.put(key, declaration);
        }
    }

    private void addSseResponses(Map<String, ResponseObject> responses, List<SchemaReference> registrations) {
        Map<String, ApiSseEvent> declarations = sseDeclarations();
        if (!isSse() || requiredHttpMethod() == Method.HEAD) return;
        Map<String, List<SchemaObject>> items = new LinkedHashMap<>();
        if (declarations.isEmpty()) items.put("200", Collections.singletonList(sseItem(null, registrations)));
        for (ApiSseEvent event : declarations.values()) items.computeIfAbsent(event.code(), key -> new ArrayList<>()).add(sseItem(event, registrations));
        for (Map.Entry<String, List<SchemaObject>> entry : items.entrySet()) {
            String code = entry.getKey();
            if (code.matches("1(?:[0-9]{2}|XX)|204|205|304")) continue;
            // An explicit response annotation owns the entire response contract for its status.
            boolean explicit = false;
            for (Annotation annotation : methodAnnotations) {
                if (annotation instanceof ApiResponse) explicit |= ((ApiResponse) annotation).code().equals(code);
                if (annotation instanceof ApiResponses) explicit |= Arrays.stream(((ApiResponses) annotation).value()).anyMatch(a -> a.code().equals(code));
            }
            for (Class<?> c = resourceClass.resourceClass; c != null && c != Object.class; c = c.getSuperclass()) {
                explicit |= Arrays.stream(c.getDeclaredAnnotationsByType(ApiResponse.class)).anyMatch(a -> a.code().equals(code));
            }
            if (explicit) continue;
            boolean distinctNames = declarations.values().stream().filter(e -> e.code().equals(code)).allMatch(e -> !e.name().isEmpty());
            SchemaObject item = entry.getValue().size() == 1 ? entry.getValue().get(0)
                : (distinctNames ? schemaObject().withOneOf(entry.getValue()) : schemaObject().withAnyOf(entry.getValue())).build();
            ResponseObject existing = responses.get(code);
            Map<String, MediaTypeObject> content = new LinkedHashMap<>();
            if (existing != null && existing.content() != null) content.putAll(existing.content());
            content.put("text/event-stream", mediaTypeObject().withItemSchema(item).build());
            responses.put(code, (existing == null ? responseObject().withDescription("Event stream") : existing.toBuilder()).withContent(content).build());
        }
    }

    private SchemaObject sseItem(@Nullable ApiSseEvent event, List<SchemaReference> registrations) {
        Class<?> payload = event == null ? String.class : event.data();
        MediaType payloadMedia = MediaType.valueOf(event == null ? "text/plain" : event.mediaType());
        SchemaObjectBuilder data = schemaObject().withType("string");
        if (event != null) data.withContentMediaType(payloadMedia.toString()).withContentSchema(SchemaReference.infer(registrations, payload, payload).build());
        Map<String, SchemaObject> properties = new LinkedHashMap<>();
        properties.put("data", data.build());
        SchemaObjectBuilder name = schemaObject().withType("string");
        if (event != null && !event.name().isEmpty()) name.withConstValue(event.name());
        properties.put("event", name.build());
        properties.put("id", schemaObject().withType("string").build());
        properties.put("retry", schemaObject().withType("integer").withMinimum(0.0).build());
        SchemaObjectBuilder item = schemaObject().withType("object").withProperties(properties)
            .withRequired(event != null && !event.name().isEmpty() ? Arrays.asList("data", "event") : Collections.singletonList("data"));
        if (event != null && !event.description().isEmpty()) item.withDescription(event.description());
        return schemaObjectCustomizer.customize(item, new SchemaObjectCustomizerContext(SchemaObjectCustomizerTarget.RESPONSE_ITEM,
            Map.class, null, resourceClass.resourceInstance, methodHandle(), null, MediaType.valueOf("text/event-stream"), null,
            event == null ? null : event.name(), event == null ? null : payload, event == null ? null : payloadMedia)).build();
    }

    private static Type unwrap(Type type) {
        for (int depth = 0; depth < 10; depth++) {
            Class<?> raw = SchemaReference.rawClass(type);
            Class<?> wrapper = raw != null && java.util.concurrent.CompletionStage.class.isAssignableFrom(raw)
                ? java.util.concurrent.CompletionStage.class : raw != null && jakarta.ws.rs.core.GenericEntity.class.isAssignableFrom(raw)
                ? jakarta.ws.rs.core.GenericEntity.class : null;
            if (wrapper == null) return type;
            Type payload = GenericTypeResolver.resolveTypeArgument(type, wrapper, 0);
            if (payload == null || payload.equals(type)) return Object.class;
            type = payload;
        }
        return Object.class;
    }

    private SchemaObjectCustomizerContext schemaContext(SchemaObjectCustomizerTarget target, @Nullable String parameter, Class<?> type, @Nullable Type parameterizedType, MediaType mediaType) {
        return new SchemaObjectCustomizerContext(target, type, parameterizedType, resourceClass.resourceInstance, methodHandle(), parameter, mediaType);
    }

    private List<ApiResponseObj> getApiResponses() {
        Map<String, List<ApiResponse>> declared = new LinkedHashMap<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = resourceClass.resourceClass; current != null && current != Object.class; current = current.getSuperclass()) hierarchy.add(current);
        Collections.reverse(hierarchy);
        for (Class<?> current : hierarchy) {
            ApiResponse[] responses = current.getDeclaredAnnotationsByType(ApiResponse.class);
            for (ApiResponse response : responses) declared.remove(response.code());
            for (ApiResponse response : responses) declared.computeIfAbsent(response.code(), key -> new ArrayList<>()).add(response);
        }
        List<ApiResponse> methodResponses = new ArrayList<>();
        for (Annotation annotation : methodAnnotations) {
            if (annotation instanceof ApiResponse) methodResponses.add((ApiResponse) annotation);
            if (annotation instanceof ApiResponses) Collections.addAll(methodResponses, ((ApiResponses) annotation).value());
        }
        for (ApiResponse response : methodResponses) declared.remove(response.code());
        for (ApiResponse response : methodResponses) declared.computeIfAbsent(response.code(), key -> new ArrayList<>()).add(response);
        List<ApiResponseObj> result = new ArrayList<>();
        for (List<ApiResponse> alternatives : declared.values()) for (ApiResponse response : alternatives) {
            Type type = response.response() == ApiResponseObj.DEFAULT.class ? genericReturnType() : response.response();
            Class<?> raw = type == null ? null : SchemaReference.rawClass(unwrap(type));
            result.add(new ApiResponseObj(response.code(), response.message(), response.responseHeaders(), raw == null ? Object.class : raw,
                type, response.contentType(), response.example()));
        }
        if (result.isEmpty() || (methodResponses.isEmpty() && declared.keySet().stream().noneMatch(code -> code.startsWith("2") || "default".equals(code)))) {
            Type type = genericReturnType() == null ? methodHandle().getReturnType() : Objects.requireNonNull(genericReturnType());
            boolean suspended = params.stream().anyMatch(p -> p.source() == ResourceMethodParam.ValueSource.SUSPENDED);
            if (suspended) type = Object.class;
            Class<?> raw = SchemaReference.rawClass(unwrap(type));
            String code = (raw == void.class || raw == Void.class) && !isSse() ? "204" : "200";
            result.add(new ApiResponseObj(code, "Success", new ResponseHeader[0], raw == null ? Object.class : raw, type, new String[0], null));
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
