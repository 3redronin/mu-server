package io.muserver.rest;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.ParamConverterProvider;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static io.muserver.rest.CORSConfig.getAllowedMethods;
import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * A handler that serves JAX-RS resources.
 *
 * @see RestHandlerBuilder#restHandler(Object...)
 */
public class RestHandler implements MuHandler {
    private static final Logger log = LoggerFactory.getLogger(RestHandler.class);

    private final RequestMatcher requestMatcher;
    private final EntityProviders entityProviders;
    private final MuHandler documentor;
    private final CustomExceptionMapper customExceptionMapper;
    private final FilterManagerThing filterManagerThing;
    private final CORSConfig corsConfig;
    private final List<ParamConverterProvider> paramConverterProviders;
    private final SchemaObjectCustomizer schemaObjectCustomizer;

    RestHandler(EntityProviders entityProviders, List<ResourceClass> roots, MuHandler documentor, CustomExceptionMapper customExceptionMapper, FilterManagerThing filterManagerThing, CORSConfig corsConfig, List<ParamConverterProvider> paramConverterProviders, SchemaObjectCustomizer schemaObjectCustomizer) {
        this.requestMatcher = new RequestMatcher(roots);
        this.entityProviders = entityProviders;
        this.documentor = documentor;
        this.customExceptionMapper = customExceptionMapper;
        this.filterManagerThing = filterManagerThing;
        this.corsConfig = corsConfig;
        this.paramConverterProviders = paramConverterProviders;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean handle(MuRequest muRequest, MuResponse muResponse) throws Exception {
        if (documentor != null && documentor.handle(muRequest, muResponse)) {
            return true;
        }
        List<MediaType> acceptHeaders;
        try {
            acceptHeaders = MediaTypeDeterminer.parseAcceptHeaders(muRequest.headers().getAll(HeaderNames.ACCEPT));
        } catch (IllegalArgumentException e) {
            throw new ClientErrorException(e.getMessage(), 400);
        }
        List<MediaType> producesRef = null;
        List<MediaType> directlyProducesRef = null;
        SecurityContext securityContext = muRequest.uri().getScheme().equals("https") ? MuSecurityContext.notLoggedInHttpsContext : MuSecurityContext.notLoggedInHttpContext;

        MuContainerRequestContext requestContext = new MuContainerRequestContext(muRequest, new LazyAccessInputStream(muRequest), Mutils.trim(muRequest.relativePath(), "/"), securityContext);
        try {
            filterManagerThing.onPreMatch(requestContext);

            Function<RequestMatcher.MatchedMethod,ResourceClass> subResourceLocator = matchedMethod -> {
                Function<ResourceMethod, Object> onSuspended = resourceMethod -> {
                    throw new MuException("Suspended is not supported on sub-resource locators. Method: " + resourceMethod.methodHandle);
                };
                ResourceMethod rm = matchedMethod.resourceMethod;
                try {
                    Object instance = invokeResourceMethod(requestContext, muResponse, matchedMethod, onSuspended, entityProviders);
                    return ResourceClass.forSubResourceLocator(rm, instance.getClass(), instance, schemaObjectCustomizer, paramConverterProviders);
                } catch (WebApplicationException wae) {
                    throw wae;
                } catch (Exception e) {
                    throw new MuException("Error creating instance returned by sub-resource-locator " + rm.methodHandle, e);
                }
            };

            RequestMatcher.MatchedMethod mm;
            try {
                mm = requestMatcher.findResourceMethod(requestContext, requestContext.getMuMethod(), acceptHeaders, subResourceLocator);
            } catch (NotAllowedException e) {
                if (requestContext.getMuMethod() == Method.HEAD) {
                    mm = requestMatcher.findResourceMethod(requestContext, Method.GET, acceptHeaders, subResourceLocator);
                } else if (requestContext.getMuMethod() == Method.OPTIONS) {
                    Set<RequestMatcher.MatchedMethod> matchedMethodsForPath = requestMatcher.getMatchedMethodsForPath(requestContext.relativePath(), subResourceLocator);
                    muResponse.headers().set(HeaderNames.ALLOW, getAllowedMethods(matchedMethodsForPath));
                    corsConfig.writeHeadersInternal(muRequest, muResponse, matchedMethodsForPath);
                    return true;
                } else {
                    throw e;
                }
            }

            corsConfig.writeHeadersInternal(muRequest, muResponse, Collections.singleton(mm));

            requestContext.setMatchedMethod(mm);
            filterManagerThing.onPostMatch(requestContext);

            List<MediaType> produces = producesRef = mm.resourceMethod.resourceClass.produces;
            List<MediaType> directlyProduces = directlyProducesRef = mm.resourceMethod.directlyProduces;

            Function<ResourceMethod, Object> suspendedParamCallback = rm -> {
                if (muRequest.isAsync()) {
                    throw new MuException("A REST method can only have one @Suspended attribute. Error for " + rm);
                }
                return new AsyncResponseAdapter(muRequest.handleAsync(),
                    response -> sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, response));
            };


            Object result = invokeResourceMethod(requestContext, muResponse, mm, suspendedParamCallback, entityProviders);

            if (!muRequest.isAsync()) {
                if (result instanceof CompletionStage) {
                    AsyncHandle asyncHandle1 = muRequest.handleAsync();
                    CompletionStage cs = (CompletionStage) result;
                    cs.thenAccept(o -> {
                        try {
                            sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, o);
                            asyncHandle1.complete();
                        } catch (Exception e) {
                            asyncHandle1.complete(e);
                        }
                    });
                } else {
                    sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, result);
                }
            }
        } catch (NotMatchedException e) {
            return false;
        } catch (Exception ex) {
            dealWithUnhandledException(0, requestContext, muResponse, ex, acceptHeaders, producesRef, directlyProducesRef);
            if (muRequest.isAsync()) {
                muRequest.handleAsync().complete();
            }
        }
        return true;
    }

    static Object invokeResourceMethod(MuContainerRequestContext requestContext, MuResponse muResponse, RequestMatcher.MatchedMethod mm, Function<ResourceMethod, Object> suspendedParamCallback, EntityProviders entityProviders) throws Exception {
        ResourceMethod rm = mm.resourceMethod;
        Object[] params = new Object[rm.methodHandle.getParameterCount()];
        for (ResourceMethodParam param : rm.params) {
            Object paramValue;
            if (param.source == ResourceMethodParam.ValueSource.MESSAGE_BODY) {
                paramValue = readRequestEntity(requestContext, rm, param.parameterHandle, entityProviders);
            } else if (param.source == ResourceMethodParam.ValueSource.CONTEXT) {
                paramValue = getContextParam(requestContext, muResponse, mm, param, entityProviders);
            } else if (param.source == ResourceMethodParam.ValueSource.SUSPENDED) {
                paramValue = suspendedParamCallback.apply(rm);
            } else {
                ResourceMethodParam.RequestBasedParam rbp = (ResourceMethodParam.RequestBasedParam) param;
                paramValue = rbp.getValue(requestContext.muRequest, mm);
            }
            params[param.index] = paramValue;
        }
        Object result = rm.invoke(params);
        return result;
    }

    private void dealWithUnhandledException(int nestingLevel, MuContainerRequestContext request, MuResponse muResponse, Exception ex, List<MediaType> acceptHeaders, List<MediaType> producesRef, List<MediaType> directlyProducesRef) throws Exception {
        Response response = customExceptionMapper.toResponse(ex);
        if (response == null && ex instanceof WebApplicationException) {
            dealWithWebApplicationException(nestingLevel, request, muResponse, (WebApplicationException) ex, acceptHeaders, producesRef == null ? emptyList() : producesRef, directlyProducesRef == null ? emptyList() : directlyProducesRef);
        } else if (response == null) {
            throw ex;
        } else {
            sendResponse(nestingLevel, request, muResponse, acceptHeaders, producesRef, directlyProducesRef, response);
        }
    }

    private void sendResponse(int nestingLevel, MuContainerRequestContext requestContext, MuResponse muResponse, List<MediaType> acceptHeaders, List<MediaType> produces, List<MediaType> directlyProduces, Object result) throws Exception {
        try {
            if (!muResponse.hasStartedSendingData()) {
                ObjWithType obj = ObjWithType.objType(result);


                if (obj.entity instanceof Exception) {
                    throw (Exception) obj.entity;
                }


                JaxRSResponse jaxRSResponse = obj.response;
                if (jaxRSResponse == null) {
                    jaxRSResponse = new JaxRSResponse(Response.Status.fromStatusCode(obj.status()), new LowercasedMultivaluedHashMap<>(), obj.entity, null, new NewCookie[0], emptyList(), new Annotation[0]);
                }

                MuResponseContext responseContext = new MuResponseContext(jaxRSResponse, obj, requestContext.getMuMethod() == Method.HEAD ? NullOutputStream.INSTANCE : new LazyAccessOutputStream(muResponse));
                if (obj.entity != null) {
                    MediaType responseMediaType = MediaTypeDeterminer.determine(obj, produces, directlyProduces, entityProviders.writers, acceptHeaders);
                    responseContext.setEntity(result, jaxRSResponse.getAnnotations(), responseMediaType);
                }

                filterManagerThing.onBeforeSendResponse(requestContext, responseContext);
                int status = responseContext.getStatus();
                muResponse.status(status);
                MuRuntimeDelegate.writeResponseHeaders(requestContext.muRequest.uri(), jaxRSResponse, muResponse);

                Object entity = responseContext.getEntity();
                if (entity == null) {
                    if (status != 204 && status != 304 && status != 205) {
                        muResponse.headers().set(HeaderNames.CONTENT_LENGTH, HeaderValues.ZERO);
                    }
                } else {

                    MediaType responseMediaType = responseContext.getMediaType();
                    Annotation[] entityAnnotations = responseContext.getEntityAnnotations();

                    Class entityType = responseContext.getEntityClass();
                    Type entityGenericType = responseContext.getEntityType();
                    MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(entityType, entityGenericType, entityAnnotations, responseMediaType);

                    long size = messageBodyWriter.getSize(entity, entityType, entityGenericType, entityAnnotations, responseMediaType);
                    if (size > -1) {
                        muResponse.headers().set(HeaderNames.CONTENT_LENGTH.toString(), size);
                    }

                    String contentType = responseMediaType.toString();
                    if (responseMediaType.getType().equals("text") && !responseMediaType.getParameters().containsKey("charset")) {
                        contentType += ";charset=utf-8";
                    }
                    muResponse.headers().set(HeaderNames.CONTENT_TYPE, contentType);

                    messageBodyWriter.writeTo(entity, entityType, entityGenericType, entityAnnotations, responseMediaType, muHeadersToJaxObj(muResponse.headers()), responseContext.getEntityStream());

                }
            }
        } catch (Exception ex) {
            dealWithUnhandledException(nestingLevel + 1, requestContext, muResponse, ex, acceptHeaders, produces, directlyProduces);
        }
    }

    private void dealWithWebApplicationException(int nestingLevel, MuContainerRequestContext requestContext, MuResponse muResponse, WebApplicationException e, List<MediaType> acceptHeaders, List<MediaType> produces, List<MediaType> directlyProduces) throws Exception {
        if (muResponse.hasStartedSendingData()) {
            log.warn("A web application exception " + e + " was thrown for " + requestContext.muRequest + ", however the response code and message cannot be sent to the client as some data was already sent.");
        } else {
            Response r = e.getResponse();
            if (nestingLevel < 2) {
                Response.ResponseBuilder toSend = Response.fromResponse(r);
                if (r.getEntity() == null) {
                    toSend.type(MediaType.TEXT_HTML_TYPE);
                    String entity = "<h1>" + r.getStatus() + " " + r.getStatusInfo().getReasonPhrase() + "</h1>";
                    if (e instanceof ServerErrorException) {
                        String errorID = "ERR-" + UUID.randomUUID().toString();
                        log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + requestContext.muRequest, e);
                        toSend.entity(entity + "<p>ErrorID=" + errorID + "</p>");
                    } else {
                        toSend.entity(entity + "<p>" + Mutils.htmlEncode(e.getMessage()) + "</p>");
                    }
                }
                sendResponse(nestingLevel + 1, requestContext, muResponse, acceptHeaders, produces, directlyProduces, toSend.build());
            } else {
                muResponse.status(r.getStatus());
                muResponse.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                Response.StatusType statusInfo = r.getStatusInfo();
                String message = statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage();
                muResponse.write(message);
            }
        }
    }

    private static Object getContextParam(MuContainerRequestContext requestContext, MuResponse muResponse, RequestMatcher.MatchedMethod mm, ResourceMethodParam param, EntityProviders providers) {
        MuRequest request = requestContext.muRequest;
        Object paramValue;
        Class<?> type = param.parameterHandle.getType();
        if (type.equals(UriInfo.class)) {
            paramValue = createUriInfo(requestContext.relativePath(), mm, request.uri().resolve(request.contextPath() + "/"), request.uri());
        } else if (type.equals(MuResponse.class)) {
            paramValue = muResponse;
        } else if (type.equals(MuRequest.class)) {
            paramValue = request;
        } else if (type.equals(HttpHeaders.class)) {
            paramValue = new JaxRsHttpHeadersAdapter(request.headers(), request.cookies());
        } else if (type.equals(SecurityContext.class)) {
            return requestContext.getSecurityContext();
        } else if (type.equals(Sse.class)) {
            return new JaxSseImpl();
        } else if (type.equals(SseEventSink.class)) {
            AsyncSsePublisher pub = AsyncSsePublisher.start(requestContext.muRequest, muResponse);
            return new JaxSseEventSinkImpl(pub, muResponse, providers);
        } else if (type.equals(ContainerRequestContext.class)) {
            return requestContext;
        } else {
            throw new ServerErrorException("MuServer does not support @Context parameters with type " + type, 500);
        }
        return paramValue;
    }

    static MuUriInfo createUriInfo(String relativePath, RequestMatcher.MatchedMethod mm, URI baseUri, URI requestUri) {
        List<String> matchedURIs = new ArrayList<>();
        matchedURIs.add(relativePath);
        ResourceMethod rm = null;
        if (mm != null) {
            String methodSpecific = mm.pathMatch.regexMatcher().group();
            matchedURIs.add(relativePath.replace("/" + methodSpecific, ""));
            rm = mm.resourceMethod;
        }
        List<Object> matchedResources = rm == null ? emptyList() : Collections.unmodifiableList(singletonList(mm.resourceMethod.resourceClass.resourceInstance));
        return new MuUriInfo(baseUri, requestUri,
            Mutils.trim(relativePath, "/"), Collections.unmodifiableList(matchedURIs),
            matchedResources);
    }

    @SuppressWarnings("unchecked")
    private static Object readRequestEntity(MuContainerRequestContext requestContext, ResourceMethod rm, Parameter parameter, EntityProviders entityProviders) throws java.io.IOException {
        MultivaluedMap<String, String> headers = requestContext.getHeaders();
        InputStream inputStream = requestContext.getEntityStream();
        Annotation[] annotations = parameter.getDeclaredAnnotations();

        // Section 4.2.1 - determine message body reader

        // 1. Obtain the media type of the request. If the request does not contain a Content-Type header then use application/octet-stream
        MediaType requestBodyMediaType = requestContext.muRequest.headers().contentType();
        if (requestBodyMediaType == null) {
            requestBodyMediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }

        // 2. Identify the Java type of the parameter whose value will be mapped from the entity body.
        Class<?> type = parameter.getType();
        Type genericType = parameter.getParameterizedType();


        // 3 & 4: Select a reader that supports the media type of the request and isReadable
        MessageBodyReader messageBodyReader = entityProviders.selectReader(type, genericType, annotations, requestBodyMediaType);
        try {
            return messageBodyReader.readFrom(type, genericType, annotations, requestBodyMediaType, headers, inputStream);
        } catch (NoContentException nce) {
            throw new BadRequestException("No request body was sent to the " + parameter.getName() + " parameter of " + rm.methodHandle
                + " - if this should be optional then specify an @DefaultValue annotation on the parameter", nce);
        }
    }

}
