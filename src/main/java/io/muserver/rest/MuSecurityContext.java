package io.muserver.rest;

import jakarta.ws.rs.core.SecurityContext;
import org.jspecify.annotations.Nullable;

import java.security.Principal;

class MuSecurityContext implements SecurityContext {

    private final @Nullable Principal principal;
    private final Authorizer authorizer;
    private final boolean isHttps;
    private final @Nullable String authenticationScheme;

    MuSecurityContext(@Nullable Principal principal, Authorizer authorizer, boolean isHttps, @Nullable String authenticationScheme) {
        this.principal = principal;
        this.authorizer = authorizer;
        this.isHttps = isHttps;
        this.authenticationScheme = authenticationScheme;
    }

    @Override
    public @Nullable Principal getUserPrincipal() {
        return principal;
    }

    @Override
    public boolean isUserInRole(String role) {
        return principal != null && authorizer.isInRole(principal, role);
    }

    @Override
    public boolean isSecure() {
        return isHttps;
    }

    @Override
    public @Nullable String getAuthenticationScheme() {
        return authenticationScheme;
    }

    static final MuSecurityContext notLoggedInHttpContext = new MuSecurityContext(null, (principal1, role) -> false, false, null);
    static final MuSecurityContext notLoggedInHttpsContext = new MuSecurityContext(null, (principal1, role) -> false, true, null);

}
