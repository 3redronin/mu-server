# mu-server

Experimental web server, very much under construction

## Principles

* HTTPS is as simple as HTTP
* The fluent API interface should mean it is easy to create web servers without referring to example code.
* Advanced options still available, such as async handlers, controlling stream flow, etc
* The dependencies should be kept to a minimum and all be  compile-time dependencies
* All config via constructors or builders, so we do not assume or impose any dependency injection frameworks.

## Maven config

````xml
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>mu-server</artifactId>
    <version>0.2.2</version>
</dependency>
````

## Routing

Handlers are added to the server builder and executed one by one until a suitable handler is found.
You can register a route with a URI template and then capture the path parameters:

````java
MuServer server = MuServerBuilder.httpsServer()
    .addHandler(Method.GET, "/blah/{id}",
        (request, response, pathParams) -> {
            response.write("The ID is " + pathParams.get("id"));
        })
    .start();
````

...or you can register a handler that can match against any URL. Returning `true` means the handler has handled the
request and no more handlers should be executed; `false` means it will continue to the next handler.

````java
MuServer server = MuServerBuilder.httpsServer()
    .addHandler((request, response) -> {
            response.write("Hello world");
            return true;
        })
    .start();
````

## JAX-RS REST Resources

Mu-Server provides a partial implementation of the [JAX-RS 2.0 spec](http://download.oracle.com/otn-pub/jcp/jaxrs-2_0-fr-eval-spec/jsr339-jaxrs-2.0-final-spec.pdf), 
which is a Java standard used to define REST resources.

Following the principle that you should be in charge of your own config and class instantiation, any parts
of the spec dealing with reflection, dependency injection, config, or service discovery are not implemented.
See the [rest/README.md](https://github.com/3redronin/mu-server/blob/master/src/main/java/io/muserver/rest/README.md) file for a full list of what is implemented from the spec.

Example REST resource class:

````java
@Path("api/fruits")
public class Fruit {

    @GET
    public String getAll() {
        return "[ { \"name\": \"apple\" }, { \"name\": \"orange\" } ]";
    }

    @GET
    @Path("{name}")
    public String get(@PathParam("name") String name) {
        switch (name) {
            case "apple":
                return "{ \"name\": \"apple\" }";
            case "orange":
                return "{ \"name\": \"orange\" }";
        }
        throw new javax.ws.rs.NotFoundException();
    }
}
````

A web server with this registered can be created like so:

````java
MuServer server = MuServerBuilder.httpsServer()
    .addHandler(RestHandlerBuilder.restHandler(new Fruit()).build())
    .start();
````

Making a `GET` request to `server.uri().resolve("/api/fruits/orange")` in this case would return the JSON
snippet corresponding to the Orange case.
