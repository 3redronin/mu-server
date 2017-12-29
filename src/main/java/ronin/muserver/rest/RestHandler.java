package ronin.muserver.rest;

import ronin.muserver.*;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ronin.muserver.rest.MethodMapping.jaxToMu;

public class RestHandler implements MuHandler {

    private final Object restResource;
    private final List<RestMethod> actions;

    public RestHandler(Object restResource) {
        this.restResource = restResource;
        this.actions = setupMethodInfo(restResource);
    }

    private static List<RestMethod> setupMethodInfo(Object restResource) {
        List<RestMethod> restMethods = new ArrayList<>();
        java.lang.reflect.Method[] methods = restResource.getClass().getMethods();

        Path path = restResource.getClass().getAnnotation(Path.class);
        if (path == null) {
            throw new IllegalArgumentException("The restResource must have a " + Path.class.getName() + " annotation to be eligible as a REST resource.");
        }
        for (java.lang.reflect.Method restMethod : methods) {
            Method httpMethod = getMuMethod(restMethod);
            if (httpMethod == null) {
                continue;
            }
            Path methodPath = httpMethod.getClass().getAnnotation(Path.class);
            Pattern pathPattern = Pattern.compile(methodPath == null ? path.value() : path.value() + methodPath.value());

            restMethods.add(new RestMethod(pathPattern, restMethod, httpMethod));
        }
        return restMethods;
    }

    private static Method getMuMethod(java.lang.reflect.Method restMethod) {
        Annotation[] annotations = restMethod.getAnnotations();
        Method value = null;
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> anno = annotation.annotationType();
            if (anno.getAnnotation(HttpMethod.class) != null) {
                if (value != null) {
                    throw new IllegalArgumentException("The method " + restMethod + " has multiple HttpMethod annotations. Only one is allowed per method.");
                }
                value = jaxToMu(anno);
            }
        }
        return value;
    }


    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        RestMethod restMethod = RestMethod.find(actions, request);
        if (restMethod == null) {
            return false;
        }

        Object result = restMethod.methodHandle.invoke(restResource);

        response.contentType(ContentTypes.APPLICATION_JSON);
        response.write((String)result);
        return true;
    }
}
