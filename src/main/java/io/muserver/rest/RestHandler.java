package io.muserver.rest;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletionStage;

import static io.muserver.Mutils.hasValue;
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

    RestHandler(EntityProviders entityProviders, Set<ResourceClass> roots, MuHandler documentor, CustomExceptionMapper customExceptionMapper, FilterManagerThing filterManagerThing, CORSConfig corsConfig) {
        this.requestMatcher = new RequestMatcher(roots);
        this.entityProviders = entityProviders;
        this.documentor = documentor;
        this.customExceptionMapper = customExceptionMapper;
        this.filterManagerThing = filterManagerThing;
        this.corsConfig = corsConfig;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean handle(MuRequest muRequest, MuResponse muResponse) throws Exception {
        if (documentor != null && documentor.handle(muRequest, muResponse)) {
            return true;
        }
        AsyncHandle asyncHandle = null;
        List<MediaType> acceptHeaders = MediaTypeDeterminer.parseAcceptHeaders(muRequest.headers().getAll(HeaderNames.ACCEPT));
        List<MediaType> producesRef = null;
        List<MediaType> directlyProducesRef = null;
        SecurityContext securityContext = muRequest.uri().getScheme().equals("https") ? MuSecurityContext.notLoggedInHttpsContext : MuSecurityContext.notLoggedInHttpContext;
        MuContainerRequestContext requestContext = new MuContainerRequestContext(muRequest, new LazyAccessInputStream(muRequest), Mutils.trim(muRequest.relativePath(), "/"), securityContext);
        try {
            filterManagerThing.onPreMatch(requestContext);
            String relativePath = requestContext.getUriInfo().getPath(false);

            String requestContentType = muRequest.headers().get(HeaderNames.CONTENT_TYPE);
            RequestMatcher.MatchedMethod mm;
            try {
                mm = requestMatcher.findResourceMethod(requestContext.getMuMethod(), relativePath, acceptHeaders, requestContentType);
            } catch (NotAllowedException e) {
                if (requestContext.getMuMethod() == Method.HEAD) {
                    mm = requestMatcher.findResourceMethod(Method.GET, relativePath, acceptHeaders, requestContentType);
                } else if (requestContext.getMuMethod() == Method.OPTIONS) {
                    Set<RequestMatcher.MatchedMethod> matchedMethodsForPath = requestMatcher.getMatchedMethodsForPath(relativePath);
                    muResponse.headers().set(HeaderNames.ALLOW, getAllowedMethods(matchedMethodsForPath));
                    corsConfig.writeHeaders(muRequest, muResponse, matchedMethodsForPath);
                    return true;
                } else {
                    throw e;
                }
            }

            corsConfig.writeHeaders(muRequest, muResponse, Collections.singleton(mm));

            List<MediaType> produces = producesRef = mm.resourceMethod.resourceClass.produces;
            List<MediaType> directlyProduces = directlyProducesRef = mm.resourceMethod.directlyProduces;
            ResourceMethod rm = mm.resourceMethod;
            Object[] params = new Object[rm.methodHandle.getParameterCount()];

            requestContext.setMatchedMethod(mm);
            filterManagerThing.onPostMatch(requestContext);


            boolean isAsync = false;

            for (ResourceMethodParam param : rm.params) {
                Object paramValue;
                if (param.source == ResourceMethodParam.ValueSource.MESSAGE_BODY) {
                    if (requestContext.hasEntity()) {
                        paramValue = readRequestEntity(requestContentType, rm, param.parameterHandle, requestContext.getEntityStream(), entityProviders, requestContext.getHeaders());
                    } else {
                        throw new BadRequestException("No request body was sent to the " + param.parameterHandle.getName()
                            + " parameter of the resource method \"" + rm.methodHandle + "\" "
                            + "- if this should be optional then specify an @DefaultValue annotation on the parameter");
                    }
                } else if (param.source == ResourceMethodParam.ValueSource.CONTEXT) {
                    paramValue = getContextParam(requestContext, muResponse, relativePath, mm, param);
                } else if (param.source == ResourceMethodParam.ValueSource.SUSPENDED) {
                    if (isAsync) {
                        throw new MuException("A REST method can only have one @Suspended attribute. Error for " + rm);
                    }
                    isAsync = true;
                    asyncHandle = muRequest.handleAsync();
                    paramValue = new AsyncResponseAdapter(asyncHandle, response -> sendResponse(0, requestContext, muResponse, acceptHeaders, produces, directlyProduces, response));
                } else {
                    ResourceMethodParam.RequestBasedParam rbp = (ResourceMethodParam.RequestBasedParam) param;
                    paramValue = rbp.getValue(muRequest, mm);
                }
                params[param.index] = paramValue;
            }


            Object result = rm.invoke(params);
            if (!isAsync) {
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
        } catch (NotFoundException e) {
            return false;
        } catch (Exception ex) {
            if (ex instanceof WebApplicationException) {
                dealWithWebApplicationException(0, requestContext, muResponse, (WebApplicationException) ex, acceptHeaders,
                    producesRef == null ? emptyList() : producesRef, directlyProducesRef == null ? emptyList() : directlyProducesRef);
            } else {
                dealWithUnhandledException(0, requestContext, muResponse, ex, acceptHeaders, producesRef, directlyProducesRef);
            }
            if (asyncHandle != null) {
                asyncHandle.complete();
            }
        }
        return true;
    }

    private void dealWithUnhandledException(int nestingLevel, MuContainerRequestContext request, MuResponse muResponse, Exception ex, List<MediaType> acceptHeaders, List<MediaType> producesRef, List<MediaType> directlyProducesRef) throws Exception {
        Response response = customExceptionMapper.toResponse(ex);
        if (response == null) {
            throw ex;
        }
        sendResponse(nestingLevel, request, muResponse, acceptHeaders, producesRef, directlyProducesRef, response);
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
                    jaxRSResponse = new JaxRSResponse(Response.Status.fromStatusCode(obj.status()), new Headers(), obj.entity, null, new NewCookie[0]);
                }

                MuResponseContext responseContext = new MuResponseContext(jaxRSResponse, obj, requestContext.getMuMethod() == Method.HEAD ? NullOutputStream.INSTANCE :  new LazyAccessOutputStream(muResponse));
                if (obj.entity != null) {
                    Annotation[] annotations = new Annotation[0]; // TODO set this properly
                    MediaType responseMediaType = MediaTypeDeterminer.determine(obj, produces, directlyProduces, entityProviders.writers, acceptHeaders);
                    responseContext.setEntity(result, annotations, responseMediaType);
                }

                filterManagerThing.onBeforeSendResponse(requestContext, responseContext);
                muResponse.status(responseContext.getStatus());
                muResponse.headers().setAll(responseContext.muHeaders());

                for (NewCookie cookie : jaxRSResponse.getCookies().values()) {
                    muResponse.headers().add(HeaderNames.SET_COOKIE, cookie.toString());
                }

                Object entity = responseContext.getEntity();
                if (entity == null) {
                    muResponse.headers().set(HeaderNames.CONTENT_LENGTH, 0);
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

                    muResponse.headers().set(HeaderNames.CONTENT_TYPE, responseMediaType.toString());

                    messageBodyWriter.writeTo(entity, entityType, entityGenericType, entityAnnotations, responseMediaType, muHeadersToJaxObj(muResponse.headers()), responseContext.getEntityStream());

                }
            }
        } catch (WebApplicationException e) {
            dealWithWebApplicationException(nestingLevel + 1, requestContext, muResponse, e, acceptHeaders, produces, directlyProduces);
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
                        toSend.entity(entity + Mutils.htmlEncode(e.getMessage()));
                    }
                }
                sendResponse(nestingLevel + 1, requestContext, muResponse, acceptHeaders, produces, directlyProduces, toSend.build());
            } else {
                muResponse.status(r.getStatus());
                muResponse.contentType(ContentTypes.TEXT_PLAIN);
                Response.StatusType statusInfo = r.getStatusInfo();
                String message = statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage();
                muResponse.write(message);
            }
        }
    }

    private static Object getContextParam(MuContainerRequestContext requestContext, MuResponse muResponse, String relativePath, RequestMatcher.MatchedMethod mm, ResourceMethodParam param) {
        MuRequest request = requestContext.muRequest;
        Object paramValue;
        Class<?> type = param.parameterHandle.getType();
        if (type.equals(UriInfo.class)) {
            paramValue = createUriInfo(relativePath, mm, request.uri().resolve(request.contextPath() + "/"), request.uri());
        } else if (type.equals(MuResponse.class)) {
            paramValue = muResponse;
        } else if (type.equals(MuRequest.class)) {
            paramValue = request;
        } else if (type.equals(HttpHeaders.class)) {
            paramValue = new JaxRsHttpHeadersAdapter(request.headers(), request.cookies());
        } else if (type.equals(SecurityContext.class)) {
            return requestContext.getSecurityContext();
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
    private static Object readRequestEntity(String requestContentType, ResourceMethod rm, Parameter parameter, InputStream inputStream, EntityProviders entityProviders, MultivaluedMap<String, String> headers) throws java.io.IOException {
        Annotation[] annotations = parameter.getDeclaredAnnotations();

        // Section 4.2.1 - determine message body reader

        // 1. Obtain the media type of the request. If the request does not contain a Content-Type header then use application/octet-stream
        MediaType requestBodyMediaType = hasValue(requestContentType) ? MediaType.valueOf(requestContentType) : MediaType.APPLICATION_OCTET_STREAM_TYPE;

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
