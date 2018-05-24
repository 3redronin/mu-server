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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.*;

import static io.muserver.Mutils.hasValue;
import static io.muserver.rest.JaxRSResponse.muHeadersToJax;
import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.util.Collections.singletonList;

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
                } else {
                    ResourceMethodParam.RequestBasedParam rbp = (ResourceMethodParam.RequestBasedParam) param;
                    paramValue = rbp.getValue(request, mm);
                }
                params[param.index] = paramValue;
            }


            Object result = rm.invoke(params);
            if (!muResponse.hasStartedSendingData()) {
                ObjWithType obj = ObjWithType.objType(result);

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

                    try (OutputStream entityStream = muResponse.outputStream()) {
                        messageBodyWriter.writeTo(obj.entity, obj.type, obj.genericType, annotations, responseMediaType, muHeadersToJaxObj(muResponse.headers()), entityStream);
                    }

                }
            }
        } catch (NotFoundException e) {
            return false;
        } catch (WebApplicationException e) {
            if (e instanceof ServerErrorException) {
                log.info("Server error for " + request, e);
            }
            Response r = e.getResponse();
            muResponse.status(r.getStatus());
            muResponse.contentType(ContentTypes.TEXT_PLAIN);
            Response.StatusType statusInfo = r.getStatusInfo();
            muResponse.write(statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage());
        } catch (Exception ex) {
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
        return true;
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
        private EmptyInputStream() {}
        public int read() {
            return -1;
        }
    }

}
