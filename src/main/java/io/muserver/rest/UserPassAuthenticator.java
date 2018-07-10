package io.muserver.rest;

import javax.ws.rs.core.SecurityContext;
import java.security.Principal;

/**
 * An authenticator used by {@link BasicAuthSecurityFilter} which can look up a user based on a username and password.
 */
public interface UserPassAuthenticator {
    /**
     * <p>Looks up a user.</p>
     * <p>It is required that the user object implements the Principal interface, so if you have custom classes for
     * users you may need to wrap them to include this.</p>
     * <p>You can later get the principle from a {@link javax.ws.rs.core.SecurityContext} (using the <code>@Context</code>
     * annotation on a REST method) and cast {@link SecurityContext#getUserPrincipal()} to your custom class.</p>
     * @param username The username
     * @param password The password
     * @return The user, or <code>null</code> if the credentials are invalid.
     */
    Principal authenticate(String username, String password);
}
