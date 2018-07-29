package io.muserver.rest;


import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scaffolding.MuAssert;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.Mutils.htmlEncode;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class BasicAuthTest {
    public int port = 0;
    public MuServer server;
    private final Map<String, Map<String, List<String>>> usersToPasswordToRoles = new HashMap<>();

    private static class MyUser implements Principal {
        private final String name;
        private final List<String> roles;
        private MyUser(String name, List<String> roles) {
            this.name = name;
            this.roles = roles;
        }
        @Override
        public String getName() {
            return name;
        }
        public boolean isInRole(String role) {
            return roles.contains(role);
        }
    }

    private UserPassAuthenticator authenticator = new UserPassAuthenticator() {
        @Override
        public Principal authenticate(String username, String password) {
            Principal principal = null;
            Map<String, List<String>> user = usersToPasswordToRoles.get(username);
            if (user != null) {
                List<String> roles = user.get(password);
                if (roles != null) {
                    principal = new MyUser(username, roles);
                }
            }
            return principal;
        }
    };
    private Authorizer authorizer = new Authorizer() {
        @Override
        public boolean isInRole(Principal principal, String role) {
            if (principal == null) {
                return false;
            }
            MyUser user = (MyUser)principal;
            return user.isInRole(role);
        }
    };


    @Path("/things")
    @Produces("text/plain")
    private static class Thing {

        @GET
        @Path("/read")
        public String readStuff(@Context SecurityContext securityContext) {
            if (!securityContext.isUserInRole("User")) {
                throw new ClientErrorException("This requires a User role", 403);
            }
            return "Reading stuff securely? " + securityContext.isSecure();
        }

        @GET
        @Path("/admin")
        public String doAdmin(@Context SecurityContext securityContext) {
            if (!securityContext.isUserInRole("Admin")) {
                throw new ClientErrorException("This requires an Admin role", 403);
            }
            return "Doing admin";
        }

    }

    @Test
    public void callingReadWithNoAuthHeaderReturns401() {
        try (Response resp = call(request().url(server.uri().resolve("/things/read").toString()))) {
            assertThat(resp.code(), is(401));
            assertThat(resp.header("WWW-Authenticate"), is("Basic realm=\"My-App\""));
        }
    }

    @Test
    public void callingRootPathDoesNotInvokeBasicAuth() {
        try (Response resp = call(request().url(server.uri().resolve("/").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("WWW-Authenticate"), is(nullValue()));
        }
    }

    @Test
    public void frankCanReadStuff() throws IOException {
        try (Response resp = call(request()
            .url(server.uri().resolve("/things/read").toString())
            .header(HttpHeaders.AUTHORIZATION, "Basic " + base64Encode("Frank:password123"))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Reading stuff securely? true"));
        }
    }

    @Test
    public void frankCannotAdminStuff() throws IOException {
        try (Response resp = call(request()
            .url(server.uri().resolve("/things/admin").toString())
            .header(HttpHeaders.AUTHORIZATION, "Basic " + base64Encode("Frank:password123"))
        )) {
            assertThat(resp.code(), is(403));
            assertThat(resp.body().string(), is("<h1>403 Forbidden</h1>This requires an Admin role"));
        }
    }

    public static String base64Encode(String value) throws UnsupportedEncodingException {
        return Base64.getEncoder().encodeToString(value.getBytes("UTF-8"));
    }

    @Before
    public void setup() {


        usersToPasswordToRoles.put("Daniel", singletonMap("s@curePa55word!", asList("User", "Admin")));
        usersToPasswordToRoles.put("Frank", singletonMap("password123", asList("User")));

        StringBuilder html = new StringBuilder();
        html.append("<html><head><title>Basic Auth Demo</title><style>th, td { padding: 20px; }</style></head><body><h1>Users</h1><table><thead><tr><th>Name</th><th>Password</th><th>Roles</th></tr></thead><tbody>");
        for (Map.Entry<String, Map<String, List<String>>> user : usersToPasswordToRoles.entrySet()) {
            html.append("<tr><td>" + htmlEncode(user.getKey()) + "</td>");
            Map.Entry<String, List<String>> entry = user.getValue().entrySet().stream().findFirst().get();
            html.append("<td>" + htmlEncode(entry.getKey()) + "</td><td>" + htmlEncode(entry.getValue().stream().collect(Collectors.joining(", "))) + "</td></tr>");
        }

        html.append("</tbody></table>");

        html.append("<h2>End points</h2><ul>" +
            "<li><a href=\"/things/read\">Read (requires User role)</a></li>" +
            "<li><a href=\"/things/admin\">Admin (requires Admin role)</a></li>" +
            "</ul>");

        html.append("</body></html>");

        server = httpsServer()
            .withHttpsPort(port)
            .addHandler(
                RestHandlerBuilder.restHandler(new Thing())
                    .addRequestFilter(new BasicAuthSecurityFilter("My-App", authenticator, authorizer))
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.contentType("text/html");
                response.write(html.toString());
            })
            .start();
    }

    @Test
    public void basicAuthWorks() {

    }


    @After
    public void stop() {
        MuAssert.stopAndCheck(server);
    }
}
