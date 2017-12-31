package ronin.muserver.rest;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;

import static ronin.muserver.Mutils.urlEncode;

public class PathMatch {

    private final boolean matches;
    private final Map<String, String> params;

    private PathMatch(boolean matches, Map<String, String> params) {
        this.matches = matches;
        this.params = params;
    }

    public static PathMatch match(String template, String path) {


        return new PathMatch(false, Collections.emptyMap());
    }

    public static Pattern uriTemplateToRegex(String template) {
        // Following jax_rs-1_1 spec, section 3.7.3 http://download.oracle.com/otn-pub/jcp/jaxrs-1.1-mrel-eval-oth-JSpec/jax_rs-1_1-mrel-spec.pdf
        // Numbered comments are direct from the spec

        StringBuilder regex = new StringBuilder("/");
        String[] bits = template.split("/");
        for (String bit : bits) {
            if (bit.length() == 0) {
                continue;
            }
            boolean isVar = bit.startsWith("{") && bit.endsWith("}");
            if (!isVar) {
                // 1. URI encode the template, ignoring URI template variable speciﬁcations.
                // 2. Escape any regular expression characters in the URI template, again ignoring URI template variable specifications.
                regex.append(Pattern.quote(urlEncode(bit)));
            } else {
                // 3. Replace each URI template variable with a capturing group containing the speciﬁed regular expression or ‘([ˆ/]+?)’ if no regular expression is speciﬁed.
                String groupName = bit.substring(1, bit.length() - 1).trim();
                String groupRegex = "[ˆ/]+?";
                if (groupName.contains(":")) {
                    String[] nameInfo = groupName.split("\\s*:\\s*", 2);
                    groupName = nameInfo[0];
                    groupRegex = nameInfo[1];
                }
                regex.append("(?<").append(groupName).append(">").append(groupRegex).append(")");
            }
            regex.append('/');
        }

        // 4. If the resulting string ends with ‘/’ then remove the ﬁnal character.
        if (regex.lastIndexOf("/") == regex.length() - 1) {
            regex.delete(regex.length() - 1, regex.length());
        }

        // 5. Append ‘(/.*)?’ to the result.
        regex.append("(/.*)?");

        return Pattern.compile(regex.toString());

    }


    public boolean matches() {
        return matches;
    }

    public Map<String, String> params() {
        return params;
    }
}
