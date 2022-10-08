package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static io.muserver.rest.CORSConfig.getAllowedMethods;
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
    private final List<ReaderInterceptor> readerInterceptors;
    private final List<WriterInterceptor> writerInterceptors;
    private final CollectionParameterStrategy collectionParameterStrategy;

    RestHandler(EntityProviders entityProviders, List<ResourceClass> roots, MuHandler documentor, CustomExceptionMapper customExceptionMapper, FilterManagerThing filterManagerThing, CORSConfig corsConfig, List<ParamConverterProvider> paramConverterProviders, SchemaObjectCustomizer schemaObjectCustomizer, List<ReaderInterceptor> readerInterceptors, List<WriterInterceptor> writerInterceptors, CollectionParameterStrategy collectionParameterStrategy) {
        this.requestMatcher = new RequestMatcher(roots);
        this.entityProviders = entityProviders;
        this.documentor = documentor;
        this.customExceptionMapper = customExceptionMapper;
        this.filterManagerThing = filterManagerThing;
        this.corsConfig = corsConfig;
        this.paramConverterProviders = paramConverterProviders;
        this.schemaObjectCustomizer = schemaObjectCustomizer;
        this.readerInterceptors = readerInterceptors;
        this.writerInterceptors = writerInterceptors;
        this.collectionParameterStrategy = collectionParameterStrategy;
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

        JaxRSRequest requestContext = new JaxRSRequest(muRequest, muResponse, new LazyAccessInputStream(muRequest), Mutils.trim(muRequest.relativePath(), "/"), securityContext, readerInterceptors, entityProviders);
        try {
            filterManagerThing.onPreMatch(requestContext);

            Function<RequestMatcher.MatchedMethod,ResourceClass> subResourceLocator = matchedMethod -> {
                Function<ResourceMethod, Object> onSuspended = resourceMethod -> {
                    throw new MuException("Suspended is not supported on sub-resource locators. Method: " + resourceMethod.methodHandle);
                };
                ResourceMethod rm = matchedMethod.resourceMethod;
                try {
                    Object instance = invokeResourceMethod(requestContext, muResponse, matchedMethod, onSuspended, entityProviders, collectionParameterStrategy);
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

            List<MediaType> produces = producesRef = mm.resourceMethod.resourceClass.produces;
            List<MediaType> directlyProduces = directlyProducesRef = mm.resourceMethod.directlyProduces;
            Annotation[] methodAnnotations = mm.resourceMethod.methodAnnotations;

            filterManagerThing.onPostMatch(requestContext);

            Function<ResourceMethod, Object> suspendedParamCallback = rm -> {
                if (muRequest.isAsync()) {
                    throw new MuException("A REST method can only have one @Suspended attribute. Error for " + rm);
                }
                return new AsyncResponseAdapter(muRequest.handleAsync(),
                    response -> sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, methodAnnotations, response));
            };


            Object result = invokeResourceMethod(requestContext, muResponse, mm, suspendedParamCallback, entityProviders, collectionParameterStrategy);

            if (!muRequest.isAsync()) {
                if (result instanceof CompletionStage) {
                    AsyncHandle asyncHandle1 = muRequest.handleAsync();
                    CompletionStage cs = (CompletionStage) result;
                    cs.thenAccept(o -> {
                        try {
                            sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, methodAnnotations, o);
                            asyncHandle1.complete();
                        } catch (Exception e) {
                            asyncHandle1.complete(e);
                        }
                    });
                } else {
                    sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, methodAnnotations, result);
                }
            }
        } catch (NotMatchedException e) {
            return false;
        } catch (Exception ex) {
            if (producesRef == null) producesRef = emptyList();
            if (directlyProducesRef == null) directlyProducesRef = emptyList();
            dealWithUnhandledException(0, requestContext, muResponse, ex, acceptHeaders, producesRef, directlyProducesRef);
            if (muRequest.isAsync()) {
                muRequest.handleAsync().complete();
            }
        }
        return true;
    }

    static Object invokeResourceMethod(JaxRSRequest requestContext, MuResponse muResponse, RequestMatcher.MatchedMethod mm, Function<ResourceMethod, Object> suspendedParamCallback, EntityProviders entityProviders, CollectionParameterStrategy collectionParameterStrategy) throws Exception {
        ResourceMethod rm = mm.resourceMethod;
        Object[] params = new Object[rm.methodHandle.getParameterCount()];
        for (ResourceMethodParam param : rm.params) {
            Object paramValue;
            if (param.source == ResourceMethodParam.ValueSource.MESSAGE_BODY) {
                paramValue = readRequestEntity(requestContext, param.parameterHandle);
            } else if (param.source == ResourceMethodParam.ValueSource.CONTEXT) {
                paramValue = getContextParam(requestContext, muResponse, mm, param, entityProviders);
            } else if (param.source == ResourceMethodParam.ValueSource.SUSPENDED) {
                paramValue = suspendedParamCallback.apply(rm);
            } else {
                ResourceMethodParam.RequestBasedParam rbp = (ResourceMethodParam.RequestBasedParam) param;
                paramValue = rbp.getValue(requestContext, mm, collectionParameterStrategy);
            }
            params[param.index] = paramValue;
        }
        return rm.invoke(params);
    }

    private void dealWithUnhandledException(int nestingLevel, JaxRSRequest request, MuResponse muResponse, Exception ex, List<MediaType> acceptHeaders, List<MediaType> producesRef, List<MediaType> directlyProducesRef) throws Exception {
        Response response = customExceptionMapper.toResponse(ex);
        if (response == null && ex instanceof WebApplicationException) {
            dealWithWebApplicationException(nestingLevel, request, muResponse, (WebApplicationException) ex, acceptHeaders, producesRef == null ? emptyList() : producesRef, directlyProducesRef == null ? emptyList() : directlyProducesRef);
        } else if (response == null) {
            throw ex;
        } else {
            sendResponse(nestingLevel, request, muResponse, acceptHeaders, producesRef, directlyProducesRef, JaxRSResponse.Builder.EMPTY_ANNOTATIONS, response);
        }
    }

    private void sendResponse(int nestingLevel, JaxRSRequest requestContext, MuResponse muResponse, List<MediaType> acceptHeaders, List<MediaType> produces, List<MediaType> directlyProduces, Annotation[] annotations, Object result) throws Exception {
        try {
            if (requestContext.hasEntity()) {
                requestContext.getEntityStream().close();
            }
            if (!muResponse.hasStartedSendingData()) {
                ObjWithType obj = ObjWithType.objType(result);

                if (obj.entity instanceof Exception) {
                    throw (Exception) obj.entity;
                }

                JaxRSResponse jaxRSResponse = obj.response;
                if (jaxRSResponse == null) {
                    jaxRSResponse = new JaxRSResponse(Response.Status.fromStatusCode(obj.status()), new LowercasedMultivaluedHashMap<>(), obj, new NewCookie[0], emptyList(), JaxRSResponse.Builder.EMPTY_ANNOTATIONS);
                }

                try (LazyAccessOutputStream out = new LazyAccessOutputStream(muResponse)) {
                    jaxRSResponse.setEntityStream(requestContext.getMuMethod() == Method.HEAD ? NullOutputStream.INSTANCE : out);
                    jaxRSResponse.setRequestContext(requestContext);

                    Annotation[] writerAnnontations = annotations;
                    if (jaxRSResponse.getAnnotations().length > 0) {
                        if (writerAnnontations.length == 0) {
                            writerAnnontations = jaxRSResponse.getAnnotations();
                        } else {
                            writerAnnontations = Arrays.copyOf(annotations, annotations.length + jaxRSResponse.getAnnotations().length);
                            System.arraycopy(jaxRSResponse.getAnnotations(), 0, writerAnnontations, annotations.length, jaxRSResponse.getAnnotations().length);
                        }
                    }

                    if (obj.entity != null) {
                        MediaType responseMediaType = MediaTypeDeterminer.determine(obj, produces, directlyProduces, entityProviders.writers, acceptHeaders, writerAnnontations);
                        jaxRSResponse.setMediaType(responseMediaType);
                    }

                    filterManagerThing.onBeforeSendResponse(requestContext, jaxRSResponse);

                    if (jaxRSResponse.hasEntity()) {
                        jaxRSResponse.executeInterceptors(writerInterceptors); // run the interceptors
                    }
                    Object entity = jaxRSResponse.getEntity();
                    if (entity instanceof Exception) {
                        throw (Exception) entity;
                    }

                    int status = jaxRSResponse.getStatus();
                    muResponse.status(status);

                    if (entity == null) {
                        if (status != 204 && status != 304 && status != 205) {
                            jaxRSResponse.getHeaders().putSingle("content-length", "0");
                        }
                        MuRuntimeDelegate.writeResponseHeaders(requestContext.muRequest.uri(), jaxRSResponse, muResponse);
                    } else {

                        MediaType responseMediaType = jaxRSResponse.getMediaType();

                        Class entityType = jaxRSResponse.getEntityClass();
                        Type entityGenericType = jaxRSResponse.getEntityType();
                        MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(entityType, entityGenericType, writerAnnontations, responseMediaType);

                        long size = messageBodyWriter.getSize(entity, entityType, entityGenericType, writerAnnontations, responseMediaType);
                        if (size > -1) {
                            jaxRSResponse.getHeaders().putSingle("content-length", size);
                        }

                        String contentType = responseMediaType.toString();
                        if (responseMediaType.getType().equals("text") && !responseMediaType.getParameters().containsKey("charset")) {
                            contentType += ";charset=utf-8";
                        }
                        jaxRSResponse.getHeaders().putSingle("content-type", contentType);

                        MuRuntimeDelegate.writeResponseHeaders(requestContext.muRequest.uri(), jaxRSResponse, muResponse);

                        messageBodyWriter.writeTo(jaxRSResponse.getEntity(), jaxRSResponse.getType(), jaxRSResponse.getGenericType(), writerAnnontations,
                            jaxRSResponse.getMediaType(), jaxRSResponse.getHeaders(), jaxRSResponse.getOutputStream());
                    }
                }
            }
        } catch (Exception ex) {
            dealWithUnhandledException(nestingLevel + 1, requestContext, muResponse, ex, acceptHeaders, produces, directlyProduces);
        }
    }

    private void dealWithWebApplicationException(int nestingLevel, JaxRSRequest requestContext, MuResponse muResponse, WebApplicationException e, List<MediaType> acceptHeaders, List<MediaType> produces, List<MediaType> directlyProduces) throws Exception {
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
                sendResponse(nestingLevel + 1, requestContext, muResponse, acceptHeaders, produces, directlyProduces, JaxRSResponse.Builder.EMPTY_ANNOTATIONS, toSend.build());
            } else {
                muResponse.status(r.getStatus());
                muResponse.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                Response.StatusType statusInfo = r.getStatusInfo();
                String message = statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage();
                muResponse.write(message);
            }
        }
    }

    private static Object getContextParam(JaxRSRequest requestContext, MuResponse muResponse, RequestMatcher.MatchedMethod mm, ResourceMethodParam param, EntityProviders providers) {
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
        } else if (type.equals(javax.ws.rs.sse.Sse.class)) {
            return new LegacyJaxSseImpl();
        } else if (type.equals(SseEventSink.class)) {
            AsyncSsePublisher pub = AsyncSsePublisher.start(requestContext.muRequest, muResponse);
            return new JaxSseEventSinkImpl(pub, muResponse, providers);
        } else if (type.equals(javax.ws.rs.sse.SseEventSink.class)) {
            AsyncSsePublisher pub = AsyncSsePublisher.start(requestContext.muRequest, muResponse);
            return new LegacyJaxSseEventSinkImpl(pub, muResponse, providers);
        } else if (type.equals(ContainerRequestContext.class) || type.equals(Request.class)) {
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
        List<Object> matchedResources = rm == null ? emptyList() : singletonList(mm.resourceMethod.resourceClass.resourceInstance);
        return new MuUriInfo(baseUri, requestUri,
            Mutils.trim(relativePath, "/"), Collections.unmodifiableList(matchedURIs),
            matchedResources);
    }

    private static Object readRequestEntity(JaxRSRequest requestContext, Parameter parameter) throws java.io.IOException {
        requestContext.setAnnotations(parameter.getDeclaredAnnotations());
        requestContext.setType(parameter.getType());
        requestContext.setGenericType(parameter.getParameterizedType());
        return requestContext.executeInterceptors();
    }

}
