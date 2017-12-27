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
}
