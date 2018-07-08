package io.muserver.rest;

import io.muserver.Method;
import io.muserver.MuRequest;
import io.muserver.Mutils;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Variant;
import java.util.Date;
import java.util.List;

class JaxRequest implements Request {
    private final MuRequest muRequest;
    private String httpMethod;

    JaxRequest(MuRequest muRequest) {
        this.muRequest = muRequest;
        this.httpMethod = muRequest.method().name();
    }

    @Override
    public String getMethod() {
        return httpMethod;
    }
    public void setMethod(String method) {
        Mutils.notNull("method", method);
        String upper = method.toUpperCase();
        this.httpMethod = Method.valueOf(upper).name();
    }

    @Override
    public Variant selectVariant(List<Variant> variants) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        throw NotImplementedException.notYet();
    }

    @Override
    public Response.ResponseBuilder evaluatePreconditions() {
        throw NotImplementedException.notYet();
    }
}
