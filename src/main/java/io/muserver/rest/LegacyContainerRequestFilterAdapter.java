package io.muserver.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;

import java.io.IOException;

class LegacyContainerRequestFilterAdapter implements ContainerRequestFilter {
    final javax.ws.rs.container.ContainerRequestFilter original;

    public LegacyContainerRequestFilterAdapter(javax.ws.rs.container.ContainerRequestFilter original) {
        this.original = original;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        LegacyJaxRSRequestAdapter container = new LegacyJaxRSRequestAdapter((JaxRSRequest) requestContext);
        original.filter(container);
    }

}
