package io.muserver.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class FilterManagerThing {
    private final List<ContainerRequestFilter> preMatchRequestFilters;
    private final List<ContainerRequestFilter> requestFilters;
    private final List<ContainerResponseFilter> responseFilters;

    FilterManagerThing(List<ContainerRequestFilter> preMatchRequestFilters, List<ContainerRequestFilter> requestFilters, List<ContainerResponseFilter> responseFilters) {
        this.preMatchRequestFilters = sortedByPriority(preMatchRequestFilters, false);
        this.requestFilters = sortedByPriority(requestFilters, false);
        this.responseFilters = sortedByPriority(responseFilters, true);
    }

    private static <T> List<T> sortedByPriority(List<T> filters, boolean reversed) {
        List<T> sorted = new ArrayList<>(filters);
        Comparator<T> comparator = Comparator.comparingInt(FilterManagerThing::priority);
        sorted.sort(reversed ? comparator.reversed() : comparator);
        return sorted;
    }

    private static int priority(Object filter) {
        Priority priority = filter.getClass().getAnnotation(Priority.class);
        return priority == null ? Priorities.USER : priority.value();
    }

    void onPreMatch(JaxRSRequest requestContext) throws IOException {
        requestContext.setRequestFilterChainRunning(true);
        try {
            for (ContainerRequestFilter preMatchRequestFilter : preMatchRequestFilters) {
                preMatchRequestFilter.filter(requestContext);
                if (requestContext.getAbortResponse() != null) {
                    return;
                }
            }
        } finally {
            requestContext.setRequestFilterChainRunning(false);
        }
    }

    void onPostMatch(JaxRSRequest requestContext) throws IOException {
        requestContext.setRequestFilterChainRunning(true);
        try {
            for (ContainerRequestFilter requestFilter : requestFilters) {
                List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(requestFilter.getClass());
                if (requestContext.methodHasAnnotations(filterBindings)) {
                    requestFilter.filter(requestContext);
                    if (requestContext.getAbortResponse() != null) {
                        return;
                    }
                }
            }
        } finally {
            requestContext.setRequestFilterChainRunning(false);
        }
    }

    void onBeforeSendResponse(JaxRSRequest requestContext, ContainerResponseContext responseContext) throws IOException {
        requestContext.markResponseFilterChainStarted();
        for (ContainerResponseFilter responseFilter : responseFilters) {
            List<Class<? extends Annotation>> filterBindings = ResourceClass.getNameBindingAnnotations(responseFilter.getClass());
            if (requestContext.methodHasAnnotations(filterBindings)) {
                responseFilter.filter(requestContext, responseContext);
            }
        }
    }
}
