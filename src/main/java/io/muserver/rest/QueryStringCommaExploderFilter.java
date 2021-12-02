package io.muserver.rest;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import java.util.List;

/**
 * This class is being removed.
 * @deprecated This was temporarily used to work around a bug-fix in the JAX-RS implementation, however now
 * {@link RestHandlerBuilder#withCollectionParameterStrategy(CollectionParameterStrategy)} should be used instead.
 */
@PreMatching
@Deprecated
public class QueryStringCommaExploderFilter implements ContainerRequestFilter {

    private final List<String> parametersToChange;

    /**
     * Creates a new filter to exploded comma-separated values in querystring parameters
     * @param parametersToChange The names of the querystring parameters that should be changed
     */
    public QueryStringCommaExploderFilter(List<String> parametersToChange) {
        throw new NotImplementedException("This is no longer required. Please set RestHandlerBuilder.withCollectionParameterStrategy(CollectionParameterStrategy.SPLIT_ON_COMMA) instead.");
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        throw new NotImplementedException("Not implemented");
    }
}
