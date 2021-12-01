package io.muserver.rest;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * This filter converts comma-separated values in querystring parameters to lists.
 * <p>Prior to Mu-Server 0.70.0, a querystring parameter value such as <code>?value=one,two,three</code> would be split
 * into 3 values when used as a list parameter such as <code>@QueryParam("value") List&lt;String&gt; values</code>
 * however as this was a violation of the JAX-RS spec this example now results in list with a single string value of <code>one,two,three</code>.</p>
 * <p>To revert to the original behaviour, add an instance of this filter to {@link RestHandlerBuilder#addRequestFilter(ContainerRequestFilter)}</p>
 * @deprecated This exists to ease the upgrade from Mu-Server versions prior to 0.70.0. It is recommended that
 * clients are changed to specify multiple values by repeating parameter names (e.g. <code>value=one&amp;value=two&amp;value=three</code>
 * however if you wish to continue supporting comma-separated values in querystring parameters it is recommended that you
 * implement this filter yourself.
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
        this.parametersToChange = new ArrayList<>(parametersToChange);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        UriInfo uriInfo = requestContext.getUriInfo();
        UriBuilder builder = uriInfo.getRequestUriBuilder();
        MultivaluedMap<String, String> query = uriInfo.getQueryParameters();
        for (String param : parametersToChange) {
            List<String> originalValues = query.get(param);
            if (originalValues != null) {
                List<String> newValues = new ArrayList<>();
                for (String originalValue : originalValues) {
                    String[] bits = originalValue.split(",|(%2C)");
                    for (String bit : bits) {
                        if (!bit.isEmpty()) {
                            newValues.add(bit);
                        }
                    }
                }
                builder.replaceQueryParam(param, newValues.toArray());
            }
        }
        requestContext.setRequestUri(builder.build());
    }
}
