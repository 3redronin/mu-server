# mu-server

Experimental web server, very much under construction

## Principles

* HTTPS by default
* The fluent API interface should mean it is easy to create web servers without referring to example code.
* Advanced options still available, such as async handlers, controlling stream flow, etc
* The dependencies should be kept to a minimum and all be  compile-time dependencies
* All config via constructors or builders, so we do not assume or impose any dependency injection frameworks.
