package ronin.muserver.rest;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ronin.muserver.Mutils.urlEncode;

public class UriPattern {

    private final Pattern pattern;
    private final Set<String> namedGroups;

    private UriPattern(Pattern pattern, Set<String> namedGroups) {
        this.pattern = pattern;
        this.namedGroups = Collections.unmodifiableSet(namedGroups);
    }

    public String pattern() {
        return pattern.pattern();
    }

    public Set<String> namedGroups() {
        return namedGroups;
    }

    public PathMatch matcher(URI input) {
        Matcher matcher = pattern.matcher(input.getPath());
        if (matcher.matches()) {
            HashMap<String, String> params = new HashMap<>();
            for (String namedGroup : namedGroups) {
                params.put(namedGroup, matcher.group(namedGroup));
            }
            return new PathMatch(true, params);
        } else {
            return new PathMatch(false, Collections.emptyMap());
        }
    }

    public String toString() {
        return pattern.toString();
    }

    /**
     * Converts a URI Template to a regular expression, following the
     * <a href="http://download.oracle.com/otn-pub/jcp/jaxrs-1.1-mrel-eval-oth-JSpec/jax_rs-1_1-mrel-spec.pdf">JAX-RS:
     * Java™ API for RESTful Web Services specification</a> section <code>3.7.3</code>
     * @param template A string as passed to a {@link javax.ws.rs.Path} annotation, such as <code>/fruit/{name}</code>
     * @return Returns a compiled regex Pattern for the given template that will match relevant URI paths, for example <code>/\Qfruit\E/(?<name>[ˆ/]+?)(/.*)?</code>
     * @throws IllegalArgumentException If the template contains invalid regular expression, or template is null, or other errors
     */
    public static UriPattern uriTemplateToRegex(String template) throws IllegalArgumentException {
        if (template == null) {
            throw new IllegalArgumentException("template cannot be null");
        }

        // Numbered comments are direct from the spec
        Set<String> groupNames = new HashSet<>();

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
                String groupRegex;
                if (groupName.contains(":")) {
                    String[] nameInfo = groupName.split("\\s*:\\s*", 2);
                    groupName = nameInfo[0];
                    groupRegex = nameInfo[1];
                } else {
                    groupRegex = "[^/]+?";
                }
                groupNames.add(groupName);
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

        return new UriPattern(Pattern.compile(regex.toString()), groupNames);

    }
}
