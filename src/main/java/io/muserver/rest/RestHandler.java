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

import static io.muserver.rest.JaxRSResponse.muHeadersToJax;

public class RestHandler implements MuHandler {

    private final Set<ResourceClass> resources;
    private final URI baseUri = URI.create("/");
    private final RequestMatcher requestMatcher;
    private final EntityProviders entityProviders = new EntityProviders(EntityProviders.builtInReaders(), EntityProviders.builtInWriters());

    public RestHandler(Object... restResources) {
        HashSet<ResourceClass> set = new HashSet<>();
        for (Object restResource : restResources) {
            set.add(ResourceClass.fromObject(restResource));
        }

        this.resources = Collections.unmodifiableSet(set);
        this.requestMatcher = new RequestMatcher(resources);
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        URI jaxURI = baseUri.relativize(URI.create(request.uri().getPath()));
        System.out.println("jaxURI = " + jaxURI);
        try {
            RequestMatcher.MatchedMethod mm = requestMatcher.findResourceMethod(request.method(), jaxURI, request.headers().getAll(HeaderNames.ACCEPT), request.headers().get(HeaderNames.CONTENT_TYPE));
            ResourceMethod rm = mm.resourceMethod;
            System.out.println("Got " + rm);
            Object[] params = new Object[rm.methodHandle.getParameterCount()];

            int paramIndex = 0;
            for (Parameter parameter : rm.methodHandle.getParameters()) {
                Object paramValue = getPathParamValue(mm, parameter);
                if (paramValue == null) {
                    Optional<InputStream> inputStream = request.inputStream();
                    if (inputStream.isPresent()) {
                        MediaType requestBodyMediaType = MediaType.valueOf(request.headers().get(HeaderNames.CONTENT_TYPE));
                        Annotation[] annotations = parameter.getDeclaredAnnotations();
                        Class<?> type = parameter.getType();
                        Type genericType = parameter.getParameterizedType();
                        MessageBodyReader messageBodyReader = entityProviders.selectReader(type, genericType, annotations, requestBodyMediaType);
                        System.out.println("messageBodyReader = " + messageBodyReader);
                        try {
                            paramValue = messageBodyReader.readFrom(type, genericType, annotations, requestBodyMediaType, muHeadersToJax(request.headers()), inputStream.get());
                        } catch (NoContentException nce) {
                            throw new BadRequestException("No request body was sent to the " + parameter.getName() + " parameter of " + rm.methodHandle
                                + " - if this should be optional then specify an @DefaultValue annotation on the parameter",  nce);
                        }
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

            if (result == null) {
                response.status(204);
                response.headers().add(HeaderNames.CONTENT_LENGTH, 0);
            } else {

                ObjWithType obj = ObjWithType.objType(result);

                Annotation[] annotations = new Annotation[0]; // TODO set this properly

                MediaType responseMediaType = MediaTypeDeterminer.determine(obj, mm.resourceMethod.resourceClass.produces, mm.resourceMethod.directlyProduces, entityProviders.writers, request.headers().getAll(HeaderNames.ACCEPT));
                MessageBodyWriter messageBodyWriter = entityProviders.selectWriter(obj.type, obj.genericType, annotations, responseMediaType);
                System.out.println("messageBodyWriter = " + messageBodyWriter);
                response.status(200);

                MultivaluedHashMap<String, Object> responseHeaders = new MultivaluedHashMap<>();


                long size = messageBodyWriter.getSize(obj.obj, obj.type, obj.genericType, annotations, responseMediaType);
                if (size > -1) {
                    responseHeaders.putSingle(HeaderNames.CONTENT_LENGTH.toString(), size);
                }

                for (String header : responseHeaders.keySet()) {
                    response.headers().add(header, responseHeaders.get(header));
                }

                response.headers().set(HeaderNames.CONTENT_TYPE, responseMediaType.toString());

                try (OutputStream entityStream = response.outputStream()) {
                    messageBodyWriter.writeTo(obj.obj, obj.type, obj.genericType, annotations, responseMediaType, responseHeaders, entityStream);
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

    private static Object getPathParamValue(RequestMatcher.MatchedMethod mm, Parameter parameter) {
        PathParam pp = parameter.getAnnotation(PathParam.class);
        if (pp != null) {
            String paramName = pp.value();
            return mm.pathParams.get(paramName);
        }
        return null;
    }

}
