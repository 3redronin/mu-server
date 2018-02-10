package io.muserver.rest;

import io.muserver.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NoContentException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.*;

import static io.muserver.Mutils.hasValue;
import static io.muserver.rest.JaxRSResponse.muHeadersToJax;
import static io.muserver.rest.JaxRSResponse.muHeadersToJaxObj;
import static java.util.Collections.singletonList;

public class RestHandler implements MuHandler {

    private final Set<ResourceClass> resources;
    private final URI baseUri = URI.create("/");
    private final RequestMatcher requestMatcher;
    private final EntityProviders entityProviders;

    RestHandler(EntityProviders entityProviders, List<ParamConverterProvider> paramConverterProviders, Object... restResources) {
        HashSet<ResourceClass> set = new HashSet<>();
        for (Object restResource : restResources) {
            set.add(ResourceClass.fromObject(restResource, paramConverterProviders));
        }

        this.resources = Collections.unmodifiableSet(set);
        this.requestMatcher = new RequestMatcher(resources);
        this.entityProviders = entityProviders;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse muResponse) throws Exception {
        URI jaxURI = baseUri.relativize(URI.create(request.uri().getRawPath()));
        try {
            String requestContentType = request.headers().get(HeaderNames.CONTENT_TYPE);
            List<MediaType> acceptHeaders = MediaTypeDeterminer.parseAcceptHeaders(request.headers().getAll(HeaderNames.ACCEPT));
            RequestMatcher.MatchedMethod mm = requestMatcher.findResourceMethod(request.method(), jaxURI, acceptHeaders, requestContentType);
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
                    Class<?> type = param.parameterHandle.getType();
                    if (type.equals(UriInfo.class)) {
                        List<String> matchedURIs = new ArrayList<>();
                        String methodUri = jaxURI.toString();
                        matchedURIs.add(methodUri);
                        String methodSpecific = mm.pathMatch.regexMatcher().group();
                        matchedURIs.add(methodUri.replace("/" + methodSpecific, ""));
                        paramValue = new MuUriInfo(request.uri().resolve("/"), request.uri(), matchedURIs, singletonList(rm.resourceClass.resourceInstance));
                    } else if (type.equals(MuResponse.class)) {
                        paramValue = muResponse;
                    } else if (type.equals(MuRequest.class)) {
                        paramValue = request;
                    } else {
                        throw new ServerErrorException("MuServer does not support @Context parameters with type " + type, 500);
                    }
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
                System.out.println("Server error: " + e);
                e.printStackTrace();
            }
            Response r = e.getResponse();
            muResponse.status(r.getStatus());
            muResponse.contentType(ContentTypes.TEXT_PLAIN);
            Response.StatusType statusInfo = r.getStatusInfo();
            muResponse.write(statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase() + " - " + e.getMessage());
        } catch (Exception ex) {
            try {
                muResponse.status(500);
            } catch (IllegalStateException ise) {
                System.out.println("Tried to change the response code to 500 but it's already been written so the response will be sent as " + muResponse.status());
            }
            System.out.println("Unexpected server error: " + ex);
            ex.printStackTrace();
        }
        return true;
    }

    private void writeHeadersToResponse(MuResponse muResponse, JaxRSResponse jaxResponse) {
        if (jaxResponse != null) {
            jaxResponse.writeToMuResponse(muResponse);
        }
    }

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

    private static Object getPathParamValue(RequestMatcher.MatchedMethod mm, Parameter parameter) {
        PathParam pp = parameter.getAnnotation(PathParam.class);
        if (pp != null) {
            String paramName = pp.value();
            return mm.pathParams.get(paramName);
        }
        return null;
    }

    private static class EmptyInputStream extends InputStream {
        private static final InputStream INSTANCE = new EmptyInputStream();
        private EmptyInputStream() {}
        public int read() throws IOException {
            return -1;
        }
    }

}
