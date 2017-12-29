package ronin.muserver.rest;

import ronin.muserver.Method;
import ronin.muserver.MuException;
import ronin.muserver.MuServer;

import javax.ws.rs.*;
import java.lang.annotation.Annotation;

public class MethodMapping {

    public static Class<? extends Annotation> toJaxMethod(Method method) {
        switch (method) {
            case GET:
                return GET.class;
            case POST:
                return POST.class;
            case OPTIONS:
                return OPTIONS.class;
            case PUT:
                return PUT.class;
            case DELETE:
                return DELETE.class;
            case PATCH:
                return PATCH.class;
        }
        throw new MuException(method + " is not supported by mu-server JAX RS");
    }

    public static Method jaxToMu(Class<? extends Annotation> jaxMethod) {
        if (GET.class.equals(jaxMethod)) {
            return Method.GET;
        } else if (POST.class.equals(jaxMethod)) {
            return Method.POST;
        } else if (OPTIONS.class.equals(jaxMethod)) {
            return Method.OPTIONS;
        } else if (PUT.class.equals(jaxMethod)) {
            return Method.PUT;
        } else if (DELETE.class.equals(jaxMethod)) {
            return Method.DELETE;
        } else if (PATCH.class.equals(jaxMethod)) {
            return Method.PATCH;
        }
        throw new MuException(jaxMethod.getName() + " is not supported by mu-server JAX RS");
    }
}
