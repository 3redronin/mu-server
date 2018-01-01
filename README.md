# mu-server

Experimental web server, very much under construction

## Principles

* HTTPS by default
* The fluent API interface should mean it is easy to create web servers without referring to example code.
* Advanced options still available, such as async handlers, controlling stream flow, etc
* The dependencies should be kept to a minimum and all be  compile-time dependencies
* All config via constructors or builders, so we do not assume or impose any dependency injection frameworks.

## JAX-RS REST Resources

Mu-Server provides a simple implementation of the [JAX-RS 2.0 spec](http://download.oracle.com/otn-pub/jcp/jaxrs-2_0-fr-eval-spec/jsr339-jaxrs-2.0-final-spec.pdf), 
which is a Java standard used to define REST resources.

Implemented:

* Classes with the following attributes: `@Path`, `@GET`, `@POST`, `@DELETE`, `@PUT`, `@PATCH`

Will implement:

* Automatic handling of `HEAD` and `OPTIONS` HTTP methods as per `3.3.5` in the spec.
* `@CookieParam`, `@Context`, `@PathParam`, `@QueryParam`, `@HeaderParam` on resource method parameters
* `@Consumes` and `@Produces` annotations
* `@DefaultValue` for method parameters
* From `3.3.1`: "An implementation SHOULD warn users if a non-public method carries a method designator or @Path annotation"
* Return a `400` when the request does not match the method params (`3.3.2`)
* Returning `void` (with `204` response), `Response` (where `null` results in `204`), `GenericEntity`, and primitives from resource methods.
* Exception handling as per `3.3.4` of the spec.
* As per `3.4` of spec, do not re-encode already URL-encoded paths, i.e the following are equivalent:
`@Path("widget list/{id}")` and `@Path("widget%20list/{id}")`
* Allow paths such as `@Path("widgets/{path:.+}")` to match paths like `widgets/small/a`

May implement:

* Matrix URL support (i.e. URLs that have semi-colon separators)
* `@Encoded` to prevent URL decoding of querystring parameters etc

Will not implement:

* Configuration using the `Application` construct and related methods, such as `createEndpoint`.
* Automatic instantiation of Resource classes (you can use the `new` keyword and pass dependencies into a constructor,
or any other approach you wish).
Note this implies that you must pass an instance of a Resource during mu-server creation, and the same instance is
used for all requests (rather than a new instance being made per request, as per the spec).
* Sub-resource locators
