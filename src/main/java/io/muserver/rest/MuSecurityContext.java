package io.muserver.rest;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

class MuSecurityContext implements SecurityContext {

    private final Principal principal;
    private final Authorizer authorizer;
    private final boolean isHttps;
    private final String authenticationScheme;

    MuSecurityContext(Principal principal, Authorizer authorizer, boolean isHttps, String authenticationScheme) {
        this.principal = principal;
        this.authorizer = authorizer;
        this.isHttps = isHttps;
        this.authenticationScheme = authenticationScheme;
    }

    @Override
    public Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return authorizer.isInRole(principal, role);
    }

    @Override
    public boolean isSecure() {
        return isHttps;
    }

    @Override
    public String getAuthenticationScheme() {
        return authenticationScheme;
    }

    static final MuSecurityContext notLoggedInHttpContext = new MuSecurityContext(null, (principal1, role) -> false, false, null);
    static final MuSecurityContext notLoggedInHttpsContext = new MuSecurityContext(null, (principal1, role) -> false, true, null);

}
