package io.muserver.rest;

import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;

import java.io.IOException;
import java.lang.annotation.Annotation;
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

    void onPreMatch(JaxRSRequest requestContext) throws IOException {
        for (ContainerRequestFilter preMatchRequestFilter : preMatchRequestFilters) {

            preMatchRequestFilter.filter(requestContext);
        }
    }

    void onPostMatch(JaxRSRequest requestContext) throws IOException {
        for (ContainerRequestFilter requestFilter : requestFilters) {
            List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(requestFilter.getClass());
            if (requestContext.methodHasAnnotations(filterBindings)) {
                requestFilter.filter(requestContext);
            }
        }
    }

    void onBeforeSendResponse(JaxRSRequest requestContext, ContainerResponseContext responseContext) throws IOException {
        for (ContainerResponseFilter responseFilter : responseFilters) {
            List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(responseFilter.getClass());
            if (requestContext.methodHasAnnotations(filterBindings)) {
                responseFilter.filter(requestContext, responseContext);
            }
        }
    }
}
