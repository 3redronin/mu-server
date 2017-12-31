package ronin.muserver.rest;

import java.net.URI;
import java.util.Map;

public class PathMatch {

    private final boolean matches;
    private final Map<String, String> params;

    PathMatch(boolean matches, Map<String, String> params) {
        this.matches = matches;
        this.params = params;
    }


    public boolean matches() {
        return matches;
    }

    public Map<String, String> params() {
        return params;
    }

    public static PathMatch match(String templateUri, URI requestUri) {
        return UriPattern.uriTemplateToRegex(templateUri).matcher(requestUri);
    }
}
