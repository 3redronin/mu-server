package io.muserver.rest;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;

import static io.muserver.Mutils.hasValue;
import static io.muserver.rest.JaxRSResponse.muHeadersToJax;
import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
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

    RestHandler(EntityProviders entityProviders, Set<ResourceClass> roots, MuHandler documentor) {
        this.requestMatcher = new RequestMatcher(roots);
        this.entityProviders = entityProviders;
        this.documentor = documentor;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean handle(MuRequest request, MuResponse muResponse) throws Exception {
        if (documentor != null && documentor.handle(request, muResponse)) {
            return true;
        }
        String relativePath = Mutils.trim(request.relativePath(), "/");
        try {
            String requestContentType = request.headers().get(HeaderNames.CONTENT_TYPE);
            List<MediaType> acceptHeaders = MediaTypeDeterminer.parseAcceptHeaders(request.headers().getAll(HeaderNames.ACCEPT));
            RequestMatcher.MatchedMethod mm = requestMatcher.findResourceMethod(request.method(), relativePath, acceptHeaders, requestContentType);
            ResourceMethod rm = mm.resourceMethod;
            Object[] params = new Object[rm.methodHandle.getParameterCount()];

            boolean isAsync = false;
            for (ResourceMethodParam param : rm.params) {
                Object paramValue;
                if (param.source == ResourceMethodParam.ValueSource.MESSAGE_BODY) {
                    Optional<InputStream> inputStream = request.inputStream();
                    if (inputStream.isPresent()) {
                        paramValue = readRequestEntity(request, requestContentType, rm, param.parameterHandle, inputStream.orElse(EmptyInputStream.INSTANCE), entityProviders);
                    } else {
                        throw new BadRequestException("No request body was sent to the " + param.parameterHandle.getName()
                            + " parameter of the resource method \"" + rm.methodHandle + "\" "
                            + "- if this should be optional then specify an @DefaultValue annotation on the parameter");
                    }
                } else if (param.source == ResourceMethodParam.ValueSource.CONTEXT) {
                    paramValue = getContextParam(request, muResponse, relativePath, mm, param);
                } else if (param.source == ResourceMethodParam.ValueSource.SUSPENDED) {
                    if (isAsync) {
                        throw new MuException("A REST method can only have one @Suspended attribute. Error for " + rm);
                    }
                    isAsync = true;
                    AsyncHandle asyncHandle = request.handleAsync();
                    paramValue = new AsyncResponseAdapter(asyncHandle, response -> sendResponse(request, muResponse, acceptHeaders, mm, response));
                } else {
                    ResourceMethodParam.RequestBasedParam rbp = (ResourceMethodParam.RequestBasedParam) param;
                    paramValue = rbp.getValue(request, mm);
                }
                params[param.index] = paramValue;
            }


            Object result = rm.invoke(params);
            if (!isAsync) {
                sendResponse(request, muResponse, acceptHeaders, mm, result);
            }
        } catch (NotFoundException e) {
            return false;
        } catch (Exception ex) {
            dealWithUnhandledException(request, muResponse, ex);
        }
        return true;
    }

    static void dealWithUnhandledException(MuRequest request, MuResponse muResponse, Exception ex) {
        log.warn("Unhandled error from handler for " + request, ex);
        if (!muResponse.hasStartedSendingData()) {
            String errorID = "ERR-" + UUID.randomUUID().toString();
            log.info("Sending a 500 to the client with ErrorID=" + errorID);
            try {
                muResponse.status(500);
                muResponse.contentType(ContentTypes.TEXT_PLAIN);
                muResponse.write("500 Server Error - ErrorID=" + errorID);
            } catch (Exception ex2) {
                log.info("Error while trying to send error message to client, probably because the connection is already lost.", ex2);
            }
        }
    }

    void sendResponse(MuRequest request, MuResponse muResponse, List<MediaType> acceptHeaders, RequestMatcher.MatchedMethod mm, Object result) {
        try {
            if (!muResponse.hasStartedSendingData()) {
                ObjWithType obj = ObjWithType.objType(result);


                if (obj.entity instanceof Exception) {
                    throw (Exception) obj.entity;
                }

                writeHeadersToResponse(muResponse, obj.response);
                muResponse.status(obj.status());

                if (obj.entity == null) {
                    muResponse.headers().set(HeaderNames.CONTENT_LENGTH, 0);
                } else {

                    Annotation[] annotations = new Annotation[0]; // TODO set this properly

                    MediaType responseMediaType = MediaTypeDeterminer.determine(obj, mm.resourceMethod.resourceClass.produces, mm.resourceMethod.directlyProduces, entityProviders.writers, acceptHeaders);
                    MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(obj.type, obj.genericType, annotations, responseMediaType);

                    long size = messageBodyWriter.getSize(obj.entity, obj.type, obj.genericType, annotations, responseMediaType);
                    if (size > -1) {
                        muResponse.headers().set(HeaderNames.CONTENT_LENGTH.toString(), size);
                    }

                    muResponse.headers().set(HeaderNames.CONTENT_TYPE, responseMediaType.toString());

                    // This weird thing stops the output stream being created until it's actually written to
                    OutputStream entityStream = new LazyAccessOutputStream(muResponse);

                    messageBodyWriter.writeTo(obj.entity, obj.type, obj.genericType, annotations, responseMediaType, muHeadersToJaxObj(muResponse.headers()), entityStream);

                }
            }
        } catch (WebApplicationException e) {
            if (e instanceof ServerErrorException) {
                log.info("Server error for " + request, e);
            }
            if (muResponse.hasStartedSendingData()) {
                log.warn("A web application exception " + e + " was thrown for " + request + ", however the response code and message cannot be sent to the client as some data was already sent.");
            } else {
                Response r = e.getResponse();
                muResponse.status(r.getStatus());
                muResponse.contentType(ContentTypes.TEXT_PLAIN);
                Object entity = r.getEntity();
                String message;
                if (entity != null) {
                    message = entity.toString();
                } else {
                    Response.StatusType statusInfo = r.getStatusInfo();
                    message = statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage();
                }
                muResponse.write(message);
            }
        } catch (Exception ex) {
            dealWithUnhandledException(request, muResponse, ex);
        }
    }

    private static Object getContextParam(MuRequest request, MuResponse muResponse, String relativePath, RequestMatcher.MatchedMethod mm, ResourceMethodParam param) {
        Object paramValue;
        ResourceMethod rm = mm.resourceMethod;
        Class<?> type = param.parameterHandle.getType();
        if (type.equals(UriInfo.class)) {
            List<String> matchedURIs = new ArrayList<>();
            matchedURIs.add(relativePath);
            String methodSpecific = mm.pathMatch.regexMatcher().group();
            matchedURIs.add(relativePath.replace("/" + methodSpecific, ""));
            paramValue = new MuUriInfo(request.uri().resolve(request.contextPath() + "/"), request.uri(),
                Mutils.trim(request.relativePath(), "/"), Collections.unmodifiableList(matchedURIs),
                Collections.unmodifiableList(singletonList(rm.resourceClass.resourceInstance)));
        } else if (type.equals(MuResponse.class)) {
            paramValue = muResponse;
        } else if (type.equals(MuRequest.class)) {
            paramValue = request;
        } else if (type.equals(HttpHeaders.class)) {
            paramValue = new JaxRsHttpHeadersAdapter(request);
        } else {
            throw new ServerErrorException("MuServer does not support @Context parameters with type " + type, 500);
        }
        return paramValue;
    }

    private void writeHeadersToResponse(MuResponse muResponse, JaxRSResponse jaxResponse) {
        if (jaxResponse != null) {
            jaxResponse.writeToMuResponse(muResponse);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object readRequestEntity(MuRequest request, String requestContentType, ResourceMethod rm, Parameter parameter, InputStream inputStream, EntityProviders entityProviders) throws java.io.IOException {
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
            return messageBodyReader.readFrom(type, genericType, annotations, requestBodyMediaType, muHeadersToJax(request.headers()), inputStream);
        } catch (NoContentException nce) {
            throw new BadRequestException("No request body was sent to the " + parameter.getName() + " parameter of " + rm.methodHandle
                + " - if this should be optional then specify an @DefaultValue annotation on the parameter", nce);
        }
    }

    private static class EmptyInputStream extends InputStream {
        private static final InputStream INSTANCE = new EmptyInputStream();

        private EmptyInputStream() {
        }

        public int read() {
            return -1;
        }
    }

    /**
     * An output stream based on the request output stream, but if no methods are called then the output stream is never created.
     */
    private static class LazyAccessOutputStream extends OutputStream {
        private final MuResponse muResponse;

        LazyAccessOutputStream(MuResponse muResponse) {
            this.muResponse = muResponse;
        }

        @Override
        public void write(int b) throws IOException {
            muResponse.outputStream().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            muResponse.outputStream().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            muResponse.outputStream().flush();
        }

        @Override
        public void close() throws IOException {
            muResponse.outputStream().close();
        }
    }

}
