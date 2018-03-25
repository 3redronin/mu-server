package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see SecurityRequirementObjectBuilder
 */
public class SecurityRequirementObject implements JsonWriter {

    public final Map<String, List<String>> requirements;

    SecurityRequirementObject(Map<String, List<String>> requirements) {
        notNull("requirements", requirements);
        this.requirements = requirements;
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
}
