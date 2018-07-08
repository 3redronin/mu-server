package io.muserver.rest;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;
import java.util.List;

class FilterManagerThing {
    private final List<ContainerRequestFilter> preMatchRequestFilters;
    private final List<ContainerRequestFilter> requestFilters;
    private final List<ContainerResponseFilter> responseFilters;

    FilterManagerThing(List<ContainerRequestFilter> preMatchRequestFilters, List<ContainerRequestFilter> requestFilters, List<ContainerResponseFilter> responseFilters) {
        this.preMatchRequestFilters = preMatchRequestFilters;
        this.requestFilters = requestFilters;
        this.responseFilters = responseFilters;
    }

    void onPreMatch(ContainerRequestContext requestContext) throws IOException {
        for (ContainerRequestFilter preMatchRequestFilter : preMatchRequestFilters) {
            preMatchRequestFilter.filter(requestContext);
        }
    }

    void onPostMatch(ContainerRequestContext requestContext) throws IOException {
        for (ContainerRequestFilter requestFilter : requestFilters) {
            requestFilter.filter(requestContext);
        }
    }
}
