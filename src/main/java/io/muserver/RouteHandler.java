package io.muserver;

import java.util.Map;

public interface RouteHandler {

    /**
     * A handler for a Route created with {@link MuServerBuilder#addHandler(Method, String, RouteHandler)}
     * or {@link Routes#route(Method, String, RouteHandler)}
     * @param request The request
     * @param response The response
     * @param pathParams A map of path parameters, for example <code>id</code> would equal <code>"123"</code>
     *                   if the route URI template was <code>/things/{id : [0-9]+}</code> and the requested URI was
     *                   <code>/things/123</code>
     * @throws Exception Throwing an exception will result in a <code>500</code> error code being returned.
     */
    void handle(MuRequest request, MuResponse response, Map<String,String> pathParams) throws Exception;

}
