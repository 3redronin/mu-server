package io.muserver.rest;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

class LegacySecurityContextAdapter implements SecurityContext {
    private final jakarta.ws.rs.core.SecurityContext underlying;

    public LegacySecurityContextAdapter(jakarta.ws.rs.core.SecurityContext underlying) {
        this.underlying = underlying;
    }

    @Override
    public Principal getUserPrincipal() {
        return underlying.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return underlying.isUserInRole(role);
    }

    @Override
    public boolean isSecure() {
        return underlying.isSecure();
    }

    @Override
    public String getAuthenticationScheme() {
        return underlying.getAuthenticationScheme();
    }
}

class LegacySecurityContextAdapterToJakarkta implements jakarta.ws.rs.core.SecurityContext {
    private final SecurityContext underlying;

    public LegacySecurityContextAdapterToJakarkta(SecurityContext underlying) {
        this.underlying = underlying;
    }

    @Override
    public Principal getUserPrincipal() {
        return underlying.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return underlying.isUserInRole(role);
    }

    @Override
    public boolean isSecure() {
        return underlying.isSecure();
    }

    @Override
    public String getAuthenticationScheme() {
        return underlying.getAuthenticationScheme();
    }
}
