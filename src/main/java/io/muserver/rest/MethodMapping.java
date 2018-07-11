package io.muserver.rest;

import io.muserver.Method;
import io.muserver.MuException;

import javax.ws.rs.*;
import java.lang.annotation.Annotation;

class MethodMapping {

    static Method jaxToMu(Class<? extends Annotation> jaxMethod) {
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
        } else if (HEAD.class.equals(jaxMethod)) {
            return Method.HEAD;
        }
        throw new MuException(jaxMethod.getName() + " is not supported by mu-server JAX RS");
    }
}
