package io.muserver.rest;

import io.muserver.Mutils;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.security.Principal;
import java.util.Base64;

/**
 * <p>A filter that can handle Basic Authentication</p>
 * <p>Construct this class and a username/password authenticator and a class that can check if your users are
 * in a role or not, then add it to {@link RestHandlerBuilder#addRequestFilter(ContainerRequestFilter)} which
 * will make a {@link SecurityContext} instance available (accessible with the <code>@Context</code> annotation in methods).</p>
 * <p>Note: it assumes credentials are sent with every request, otherwise a 401 is returned.</p>
 * <p>If authentication fails, then the method is still invoked, however {@link SecurityContext#getUserPrincipal()} will
 * return <code>null</code> and {@link SecurityContext#isUserInRole(String)} will return false for any role.</p>
 */
public class BasicAuthSecurityFilter implements ContainerRequestFilter {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    private Response.ResponseBuilder authResponse;
    private final UserPassAuthenticator authenticator;
    private final Authorizer authorizer;

    /**
     * Creates a new Basic Auth Security Filter
     * @param authRealm The name of your application - the client may associate credentials with this name
     * @param authenticator An object that takes a username and password and returns a user (or null)
     * @param authorizer An object that can check if a given user is in a given role.
     */
    public BasicAuthSecurityFilter(String authRealm, UserPassAuthenticator authenticator, Authorizer authorizer) {
        Mutils.notNull("authenticator", authenticator);
        Mutils.notNull("authorizer", authorizer);
        Mutils.notNull("authRealm", authRealm);
        if (authRealm.contains("\"")) {
            throw new IllegalArgumentException("authRealm cannot contain a double quote");
        }
        this.authenticator = authenticator;
        this.authorizer = authorizer;
        authResponse = Response
            .status(401)
            .entity("401 Unauthorized")
            .type(MediaType.TEXT_PLAIN_TYPE)
            .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + authRealm + "\"");

    }

    @Override
    public void filter(ContainerRequestContext filterContext) throws IOException {
        String authorization = filterContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Basic ")) {
            filterContext.abortWith(authResponse.build());
            return;
        }

        String base64Encoded = authorization.substring("Basic ".length());
        String decoded = new String(Base64.getDecoder().decode(base64Encoded), "UTF-8");
        String[] userPass = decoded.split(":", 2);
        if (userPass.length != 2) {
            filterContext.abortWith(Response.status(400).entity("An invalid " + HttpHeaders.AUTHORIZATION + " header was used").build());
            return;
        }

        Principal principal = authenticator.authenticate(userPass[0], userPass[1]);
        boolean isHttps = "https".equalsIgnoreCase(filterContext.getUriInfo().getRequestUri().getScheme());

        MuSecurityContext securityContext;
        if (principal == null) {
            securityContext = isHttps ? MuSecurityContext.notLoggedInHttpsContext :  MuSecurityContext.notLoggedInHttpContext;
        } else {
            securityContext = new MuSecurityContext(principal, authorizer, isHttps, SecurityContext.BASIC_AUTH);
        }
        filterContext.setSecurityContext(securityContext);
    }


}
