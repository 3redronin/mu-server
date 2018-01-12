package io.muserver.rest;

import io.muserver.*;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static io.muserver.Mutils.hasValue;
import static io.muserver.rest.JaxRSResponse.muHeadersToJax;

public class RestHandler implements MuHandler {

    private final Set<ResourceClass> resources;
    private final URI baseUri = URI.create("/");
    private final RequestMatcher requestMatcher;
    private final EntityProviders entityProviders;

    public RestHandler(EntityProviders entityProviders, Object... restResources) {
        HashSet<ResourceClass> set = new HashSet<>();
        for (Object restResource : restResources) {
            set.add(ResourceClass.fromObject(restResource));
        }

        this.resources = Collections.unmodifiableSet(set);
        this.requestMatcher = new RequestMatcher(resources);
        this.entityProviders = entityProviders;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        URI jaxURI = baseUri.relativize(URI.create(request.uri().getPath()));
        System.out.println("jaxURI = " + jaxURI);
        try {
            String requestContentType = request.headers().get(HeaderNames.CONTENT_TYPE);
            RequestMatcher.MatchedMethod mm = requestMatcher.findResourceMethod(request.method(), jaxURI, request.headers().getAll(HeaderNames.ACCEPT), requestContentType);
            ResourceMethod rm = mm.resourceMethod;
            System.out.println("Got " + rm);
            Object[] params = new Object[rm.methodHandle.getParameterCount()];

            int paramIndex = 0;
            for (Parameter parameter : rm.methodHandle.getParameters()) {
                Object paramValue = getPathParamValue(mm, parameter);
                if (paramValue == null) {
                    Optional<InputStream> inputStream = request.inputStream();
                    if (inputStream.isPresent()) {
                        paramValue = readRequestEntity(request, requestContentType, rm, parameter, inputStream.get(), entityProviders);
                    } else {
                        // TODO: supply default values
                        throw new BadRequestException("No request body was sent to the " + parameter.getName() + " parameter of " + rm.methodHandle
                            + " - if this should be optional then specify an @DefaultValue annotation on the parameter");
                    }
                }

                params[paramIndex] = paramValue;
                paramIndex++;
            }
            Object result = rm.invoke(params);
            System.out.println("result = " + result);
            ObjWithType obj = ObjWithType.objType(result);

            if (obj.entity == null) {
                response.status(204);
                response.headers().add(HeaderNames.CONTENT_LENGTH, 0);
            } else {

                Annotation[] annotations = new Annotation[0]; // TODO set this properly

                MediaType responseMediaType = MediaTypeDeterminer.determine(obj, mm.resourceMethod.resourceClass.produces, mm.resourceMethod.directlyProduces, entityProviders.writers, request.headers().getAll(HeaderNames.ACCEPT));
                MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(obj.type, obj.genericType, annotations, responseMediaType);

                response.status(obj.status());
                MultivaluedHashMap<String, Object> responseHeaders = new MultivaluedHashMap<>();

                long size = messageBodyWriter.getSize(obj.entity, obj.type, obj.genericType, annotations, responseMediaType);
                if (size > -1) {
                    responseHeaders.putSingle(HeaderNames.CONTENT_LENGTH.toString(), size);
                }

                for (String header : responseHeaders.keySet()) {
                    response.headers().add(header, responseHeaders.get(header));
                }

                response.headers().set(HeaderNames.CONTENT_TYPE, responseMediaType.toString());

                try (OutputStream entityStream = response.outputStream()) {
                    messageBodyWriter.writeTo(obj.entity, obj.type, obj.genericType, annotations, responseMediaType, responseHeaders, entityStream);
                }

            }
        } catch (NotFoundException e) {
            System.out.println(request.uri() + " not a JAX RS method");
            return false;
        } catch (WebApplicationException e) {
            Response r = e.getResponse();
            response.status(r.getStatus());
            response.contentType(ContentTypes.TEXT_PLAIN);
            Response.StatusType statusInfo = r.getStatusInfo();
            response.write(statusInfo.getStatusCode() + " " + statusInfo.getReasonPhrase());
        } catch (Exception ex) {
            try {
                response.status(500);
            } catch (IllegalStateException ise) {
                System.out.println("Tried to change the response code to 500 but it's already been written so the response will be sent as " + response.status());
            }
            System.out.println("Unexpected server error: " + ex);
            ex.printStackTrace();
        }
        return true;
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
        System.out.println("messageBodyReader = " + messageBodyReader);
        try {
            return messageBodyReader.readFrom(type, genericType, annotations, requestBodyMediaType, muHeadersToJax(request.headers()), inputStream);
        } catch (NoContentException nce) {
            throw new BadRequestException("No request body was sent to the " + parameter.getName() + " parameter of " + rm.methodHandle
                + " - if this should be optional then specify an @DefaultValue annotation on the parameter",  nce);
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

}
