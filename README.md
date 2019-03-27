# mu-server

A modern Java web server, based on Netty. Currently in beta.


**Please visit <https://muserver.io> for full documentation.**


[Getting started](https://muserver.io/) is as easy as:

````java
MuServer server = MuServerBuilder.httpsServer()
    .addHandler(Method.GET, "/blah/{id}",
        (request, response, pathParams) -> {
            response.write("The ID is " + pathParams.get("id"));
        })
    .start();
````

### Features

* [Flexible routing](https://muserver.io/routes)
* Convenient access to [headers, forms,query strings, cookies, request bodies and responses](https://muserver.io/model)
* [Static resource handling](https://muserver.io/resources)
* [HTTPS support](https://muserver.io/https) including [Let's Encrypt integration](https://muserver.io/letsencrypt)
* [Server Sent Events](https://muserver.io/sse)
* Built in [JAX-RS support](https://muserver.io/jaxrs)
* [File upload support](https://muserver.io/uploads)
* [Async handler support](https://muserver.io/async)
* and more. See <https://muserver.io/> for more information.