package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Specifies the security schemes required for an operation.
 *
 * @see SecurityRequirementObjectBuilder
 */
public class SecurityRequirementObject implements JsonWriter {

    private final Map<String, List<String>> requirements;

    SecurityRequirementObject(@Nullable Map<String, List<String>> requirements) {
        notNull("requirements", requirements);
        this.requirements = java.util.Objects.requireNonNull(requirements);
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        for (Map.Entry<String, List<String>> entry : requirements.entrySet()) {
            isFirst = append(writer, entry.getKey(), entry.getValue(), isFirst);
        }
        writer.write('}');
    }

    /**
     * Gets the required security schemes and scopes.
     *
     * @return the value described by {@link SecurityRequirementObjectBuilder#withRequirements}
     */
    public Map<String, List<String>> requirements() {
        return requirements;
    }
}
