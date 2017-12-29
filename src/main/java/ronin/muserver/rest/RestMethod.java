package ronin.muserver.rest;

import ronin.muserver.Method;
import ronin.muserver.MuRequest;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class RestMethod {
    final Pattern pathPattern;
    final java.lang.reflect.Method methodHandle;
    final Method httpMethod;

    public RestMethod(Pattern pathPattern, java.lang.reflect.Method methodHandle, Method httpMethod) {
        this.pathPattern = pathPattern;
        this.methodHandle = methodHandle;
        this.httpMethod = httpMethod;
    }

    public boolean matches(MuRequest request) {
        if (request.method() != httpMethod) {
            return false;
        }
        Matcher matcher = pathPattern.matcher(request.uri().getPath());
        return matcher.matches();
    }

    public static RestMethod find(List<RestMethod> all, MuRequest request) {
        for (RestMethod restMethod : all) {
            if (restMethod.matches(request)) {
                return restMethod;
            }
        }
        return null;
    }
}
