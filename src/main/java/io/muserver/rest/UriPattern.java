package io.muserver.rest;

import io.muserver.Mutils;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pattern representing a URI template, such as <code>/fruit</code> or <code>/fruit/{name}</code> etc.
 * To create a new pattern, call the static {@link #uriTemplateToRegex(String)} method.
 */
public class UriPattern {

    private static final String DEFAULT_CAPTURING_GROUP_PATTERN = "[^/]+?";
    private final Pattern pattern;
    private final Set<String> namedGroups;
    final int numberOfLiterals;

    private UriPattern(Pattern pattern, Set<String> namedGroups, int numberOfLiterals) {
        this.pattern = pattern;
        this.namedGroups = Collections.unmodifiableSet(namedGroups);
        this.numberOfLiterals = numberOfLiterals;
    }

    /**
     * @return Returns the regular expression used to do the matching
     */
    public String pattern() {
        return pattern.pattern();
    }

    /**
     * @return Returns the read-only set of path parameters in this pattern
     */
    public Set<String> namedGroups() {
        return namedGroups;
    }

    /**
     * Matches the given URI against this pattern.
     * @param input The URI to check against.
     * @return Returns a {@link PathMatch} where {@link PathMatch#matches()} is <code>true</code> if the URI matches
     * and otherwise <code>false</code>.
     */
    public PathMatch matcher(URI input) {
        Matcher matcher = pattern.matcher(input.getPath());
        if (matcher.matches()) {
            HashMap<String, String> params = new HashMap<>();
            for (String namedGroup : namedGroups) {
                params.put(namedGroup, matcher.group(namedGroup));
            }
            return new PathMatch(true, params, matcher);
        } else {
            return new PathMatch(false, Collections.emptyMap(), matcher);
        }
    }

    public String toString() {
        return pattern.toString();
    }

    /**
     * Converts a URI Template to a regular expression, following the
     * <a href="http://download.oracle.com/otn-pub/jcp/jaxrs-2_0-fr-eval-spec/jsr339-jaxrs-2.0-final-spec.pdf">JAX-RS:
     * Java™ API for RESTful Web Services specification Version 2.0</a> section <code>3.7.3</code>
     * @param template A string as passed to a {@link javax.ws.rs.Path} annotation, such as <code>/fruit/{name}</code>
     * @return Returns a compiled regex Pattern for the given template that will match relevant URI paths, for example <code>/\Qfruit\E/(?&lt;name&gt;[ˆ/]+?)(/.*)?</code>
     * @throws IllegalArgumentException If the template contains invalid regular expression, or template is null, or other errors
     */
    public static UriPattern uriTemplateToRegex(String template) throws IllegalArgumentException {
        if (template == null) {
            throw new IllegalArgumentException("template cannot be null");
        }
        template = trimSlashes(template);

        // Numbered comments are direct from the spec
        Set<String> groupNames = new HashSet<>();

        StringBuilder regex = new StringBuilder();
        int numberOfLiterals = 0;
        int curIndex = 0;
        int loop = 0;
        while (curIndex < template.length()) {
            loop++;
            int startRegex = template.indexOf('{', curIndex);
            if (startRegex != curIndex) {
                int endIndex = startRegex == -1 ? template.length() : startRegex;
                String literal = template.substring(curIndex, endIndex);
                numberOfLiterals += literal.length();
                if (literal.equals("/")) {
                    regex.append('/');
                } else if (!literal.contains("/")) {
                    regex.append(Pattern.quote(Mutils.urlEncode(literal)));
                } else {
                    String[] segments = literal.split("/");
                    for (int i = 0; i < segments.length; i++) {
                        String segment = segments[i];
                        if (!segment.isEmpty()) {
                            regex.append(Pattern.quote(Mutils.urlEncode(segment)));
                        }
                        regex.append('/');
                    }
                }
                curIndex = endIndex;
            } else {
                int endOfRegex = template.indexOf('}', curIndex);
                if (endOfRegex == -1) {
                    throw new IllegalArgumentException("Unclosed { character in path " + template);
                }
                endOfRegex++;
                String bit = template.substring(curIndex, endOfRegex);
                String groupName = bit.substring(1, bit.length() - 1).trim();
                String groupRegex;
                if (groupName.contains(":")) {
                    String[] nameInfo = groupName.split("\\s*:\\s*", 2);
                    groupName = nameInfo[0];
                    groupRegex = nameInfo[1];
                } else {
                    groupRegex = DEFAULT_CAPTURING_GROUP_PATTERN;
                }
                groupNames.add(groupName);
                regex.append("(?<").append(groupName).append(">").append(groupRegex).append(")");
                curIndex = endOfRegex;
            }
            if (loop > 100) {
                break;
            }
        }

        // 4. If the resulting string ends with '/' then remove the final character.
        if (regex.lastIndexOf("/") == regex.length() - 1) {
            regex.delete(regex.length() - 1, regex.length());
        }

        // 5. Append '(/.*)?' to the result.
        regex.append("(/.*)?");

        return new UriPattern(Pattern.compile(regex.toString()), groupNames, numberOfLiterals);

    }

    static String trimSlashes(String url) {
        boolean start = url.startsWith("/");
        boolean end = url.endsWith("/");
        if (!start && !end) {
            return url;
        }
        return url.substring(start ? 1 : 0, end ? url.length() - 1 : url.length());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UriPattern that = (UriPattern) o;
        return Objects.equals(pattern.pattern(), that.pattern.pattern());
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern.pattern());
    }

    boolean equalModuloVariableNames(UriPattern other) {
        String regex = "\\(\\?<[^>]+>";
        String thisNormalised = this.pattern().replaceAll(regex, "(");
        String otherNormalised = other.pattern().replaceAll(regex, "(");
        return thisNormalised.equals(otherNormalised);
    }
}
