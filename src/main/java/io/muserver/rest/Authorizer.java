package io.muserver.rest;

import java.security.Principal;

/**
 * A class that can check if a principle is in a given role.
 * @see BasicAuthSecurityFilter
 */
public interface Authorizer {
    /**
     * Checks if a user is in a role
     * @param principal The user, which was created by {@link UserPassAuthenticator#authenticate(String, String)}
     * @param role A role to check
     * @return Returns true if the user is in the given role; otherwise false.
     */
    boolean isInRole(Principal principal, String role);
}
