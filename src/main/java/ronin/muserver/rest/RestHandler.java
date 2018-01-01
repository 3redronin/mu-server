package ronin.muserver.rest;

import ronin.muserver.*;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class RestHandler implements MuHandler {

    private final Set<ResourceClass> resources;
    private final URI baseUri = URI.create("/");
    private final RequestMatcher requestMatcher;

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
            RequestMatcher.MatchedMethod mm = requestMatcher.findResourceMethod(request.method(), jaxURI);
            ResourceMethod rm = mm.resourceMethod;
            System.out.println("Got " + rm);
            Object[] params = new Object[rm.methodHandle.getParameterCount()];

            int paramIndex = 0;
            for (Parameter parameter : rm.methodHandle.getParameters()) {
                PathParam pp = parameter.getAnnotation(PathParam.class);
                if (pp != null) {
                    String paramName = pp.value();
                    params[paramIndex] = mm.pathMatch.params().get(paramName);
                }
                paramIndex++;
            }
            Object result = rm.invoke(params);
            System.out.println("result = " + result);

            if (result == null) {
                response.status(204);
                response.headers().add(HeaderNames.CONTENT_LENGTH, 0);
            } else {
                if (result instanceof String) {
                    String s = (String)result;
                    response.status(200);
                    response.headers().add(HeaderNames.CONTENT_LENGTH, s.length());
                    response.write(s);
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
            response.status(500);
            System.out.println("Unexpected server error: " + ex);
            ex.printStackTrace();
        }
        return true;
    }
}
