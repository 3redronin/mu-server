package io.muserver.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;

import java.io.IOException;

class LegacyContainerResponseFilterAdapter implements ContainerResponseFilter {
    final javax.ws.rs.container.ContainerResponseFilter original;

    public LegacyContainerResponseFilterAdapter(javax.ws.rs.container.ContainerResponseFilter original) {
        this.original = original;
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        LegacyJaxRSRequestAdapter req = new LegacyJaxRSRequestAdapter((JaxRSRequest) requestContext);
        LegacyJavaxRSResponse res = new LegacyJavaxRSResponse((JaxRSResponse) responseContext);
        original.filter(req, res);
    }
}
