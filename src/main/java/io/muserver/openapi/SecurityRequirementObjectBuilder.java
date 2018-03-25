package io.muserver.openapi;

import java.util.List;
import java.util.Map;

/**
 * <p>Lists the required security schemes to execute this operation. The name used for each property MUST correspond to
 * a security scheme declared in the {@link SecuritySchemeObject} under the {@link ComponentsObject}.</p>
 *
 * <p>Security Requirement Objects that contain multiple schemes require that all schemes MUST be satisfied for a request
 * to be authorized. This enables support for scenarios where multiple query parameters or HTTP headers are required to
 * convey security information.</p>
 *
 * <p>When a list of Security Requirement Objects is defined on the {@link OpenAPIObject} or
 * {@link OperationObject}, only one of Security Requirement Objects in the list needs to be
 * satisfied to authorize the request.</p>
 */
public class SecurityRequirementObjectBuilder {
    private Map<String, List<String>> requirements;

    /**
     * @param requirements Each name MUST correspond to a security scheme which is declared in the {@link ComponentsObject#securitySchemes}
     *                    under the {@link ComponentsObject}. If the security scheme is of type <code>"oauth2"</code> or
     *                     <code>"openIdConnect"</code>, then the value is a list of scope names required for the execution.
     *                     For other security scheme types, the array MUST be empty.
     * @return The builder
     */
    public SecurityRequirementObjectBuilder withRequirements(Map<String, List<String>> requirements) {
        this.requirements = requirements;
        return this;
    }

    public SecurityRequirementObject build() {
        return new SecurityRequirementObject(requirements);
    }

    /**
     * Creates a builder for a {@link SecurityRequirementObjectBuilder}
     *
     * @return A new builder
     */
    public static SecurityRequirementObjectBuilder securityRequirementObject() {
        return new SecurityRequirementObjectBuilder();
    }
}