
# Implementation of spec

This section goes into detail about which parts of the spec are implemented, which parts may be implemented
later, and which parts will never be implemented. The numbers and headers are taken directly from the
[jaxrs-2.1 final spec](https://jcp.org/aboutJava/communityprocess/final/jsr370/index.html)
provided by Oracle.


## 2 Applications

The Mu Jax-RS implementation does not support classpath scanning or definition of Resource classes, and as
such the `javax.ws.rs.core.Application` class is not supported. All resources and optional providers are 
registered programmatically using the `io.muserver.rest.RestHandlerBuilder` class.

## 3 Resources

### 3.1 Resource Classes 

The spec specifies two lifecycles for resource classes: per-request instantiation (via reflection) or
singletons, such amongst concurrent requests. Following the principle of only supporting programatic
configration, Mu-Server only supports singletons, so any constructors or fields are ignored by MuServer.

#### 3.1.1 Lifecycle and Environment 

- [x] Singletons where lifecycle is handled by the user
- [ ] Per request resources will never be implemented

#### 3.1.2 Constructors 

N/A There are no restrictions or requirements around constructors of your resources, as you instantiate your own
resource instances.

### 3.2 Fields and Bean Properties 

N/A Not applicable as only singletons supported

### 3.3 Resource Methods 

- [x] Resource methods implemented with GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD support
- [ ] Sub-resource Locators not implemented
- [ ] Custom HTTP methods not supported.

#### 3.3.1 Visibility 

- [x] Implemented
- [ ] Warn about mis-configured methods.

#### 3.3.2 Parameters 

- [x] String parameters
- [x] Primitives and boxed primitives
- [x] Enums
- [x] Static fromString method objects
- [x] Static valueOf method objects
- [x] Single-string constructor objects
- [x] `List<T>`, `Set<T>`, and `SortedSet<T>` for values satisfying above 3 cases. 
- [x] `@DefaultValue`
- [x] `@Encoded`

#### 3.3.3 Return Type 

- [x] void methods
- [x] Response objects
- [x] GenericEntity objects
- [x] Arbitrary objects (where supported by MessageBodyWriters)

#### 3.3.4 Exceptions 

- [x] 1: Use the response property of WebApplicationExceptions to send the error message
- [x] 2: Use an exception mapping provider if available
- [x] 3: Unchecked and unmapped exceptions should be thrown to the web server
- [x] 4: (not applicable to mu-server)

#### 3.3.5 HEAD and OPTIONS 

- [x] HEAD requests fall back to GET requests without a body
- [x] OPTIONS requests fall back to using jax-rs meta-data

### 3.4 URI Templates 

- [x] Implemented.

#### 3.4.1 Sub Resources 

- [x] Sub Resources supported
- [ ] Sub-resource locators not yet implemented.

### 3.5 Declaring Media Type Capabilities 

- [x] Implemented.

### 3.6 Annotation Inheritance 

- [x] Implemented.

### 3.7 Matching Requests to Resource Methods 

- [x] Implemented.

#### 3.7.1 Request Preprocessing 

- [x] Implemented.

#### 3.7.2 Request Matching 

- [x] Implemented.

#### 3.7.3 Converting URI Templates to Regular Expressions 

- [x] Implemented.

### 3.8 Determining the MediaType of Responses 

- [x] Implemented.

## 4 Providers

- [x] Implemented, aside from dependency injection and classpath scanning etc, which will never be implemented.

### 4.1 Lifecycle and Environment 

- [x] Only singletons supported.

#### 4.1.1 Automatic Discovery 

N/A. This will never be implemented. Users should explicitly and programmatically specify any providers using
the `io.muserver.rest.RestHandlerBuilder` class.

#### 4.1.2 Constructors 

N/A as Mu will never instantiate user classes.

#### 4.1.3 Priorities

No plan to implement as it would add another dependency.

### 4.2 Entity Providers 

- [x] Implemented.

#### 4.2.1 Message Body Reader 

- [x] Implemented.

#### 4.2.2 Message Body Writer 

- [x] Implemented.

#### 4.2.3 Declaring Media Type Capabilities 

- [x] Implemented.

#### 4.2.4 Standard Entity Providers 

- [x] `byte[]` All media types (*/*)
- [x] `java.lang.String` All media types (*/*)
- [x] `java.io.InputStream` All media types (*/*)
- [x] `java.io.Reader` All media types (*/*)
- [x] `java.io.File` All media types (*/*)
- [x] `javax.activation.DataSource` All media types (*/*)
- [ ] `javax.xml.transform.Source` XML types (text/xml, application/xml and media types of the form application/*+xml)
- [ ] `javax.xml.bind.JAXBElement` and application-supplied JAXB classes XML types (text/xml and application/xml and media types of the form application/*+xml)
- [x] `MultivaluedMap<String,String>` Form content (application/x-www-form-urlencoded)
- [x] `StreamingOutput` All media types (*/*), MessageBodyWriter only
- [x] `java.lang.Boolean`, `java.lang.Character`, `java.lang.Number` Only for text/plain
- [x] Corresponding primitive types supported via boxing/unboxing conversion.

#### 4.2.5 Transfer Encoding 

- [x] Implemented.

#### 4.2.6 Content Encoding 

- [x] Implemented.

### 4.3 Context Providers 

- [ ] Not implemented.

#### 4.3.1 Declaring Media Type Capabilities 

- [ ] Not implemented.

### 4.4 Exception Mapping Providers 

- [x] Implemented, except as per Mu-Server conventions, no automatic registering is used, so a `@Provider` annotation is ignored.
Call `RestHandlerBuilder.addExceptionMapper` to register mappers.

### 4.5 Exceptions 

#### 4.5.1 Server Runtime 

- [x] Implemented.

#### 4.5.2 Client Runtime 

N/A as Mu does not support Client Runtime.

## 5 Client API

N/A. This is a server-only implementation and there is no plan for a Client implementation.

## 6 Filters and Interceptors

### 6.1 Introduction 

Only server-based filters and interceptors are being implemented as there is no Mu client.

### 6.2 Filters 

- [x] `ContainerRequestFilter`
- [x] `@Prematching` causes request filter to run before matching
- [x] `ContainerResponseFilter`

### 6.3 Entity Interceptors 

- [ ] Not yet implemented.

### 6.4 Lifecycle 

N/A as Mu Server does not control the lifecycle of your objects.

### 6.5 Binding 

#### 6.5.1 Global Binding 

- [x] Implemented

#### 6.5.2 Name Binding 

- [x] Implemented

#### 6.5.3 Dynamic Binding 

Not implemented, and won't be as it uses Configuration and Feature classes.

#### 6.5.4 Binding in Client API 

N/A

### 6.6 Priorities 

No plan to implement as it would add another dependency. The order filters are added are the order they are run in.

### 6.7 Exceptions 

#### 6.7.1 Server Runtime 

- [x] Implemented

#### 6.7.2 Client Runtime 

N/A

## 7 Validation

- [ ] Not yet implemented.

### 7.1 Constraint Annotations 

- [ ] Not yet implemented.

### 7.2 Annotations and Validators 

- [ ] Not yet implemented.

### 7.3 Entity Validation 

- [ ] Not yet implemented.

### 7.4 Default Validation Mode 

- [ ] Not yet implemented.

### 7.5 Annotation Inheritance 

- [ ] Not yet implemented.

### 7.6 Validation and Error Reporting 

- [ ] Not yet implemented.

## 8 Asynchronous Processing

### 8.1 Introduction 

- [x] Implemented

### 8.2 Server API 

- [x] Parameter-based with `@Suspended AsyncResponse`

#### 8.2.1 Timeouts and Callbacks

- [x] Timeouts. 
- [x] `CompletionCallback`
- [x] `CompletionCallback` with unhandled exception in callback parameter.
- [x] `ConnectionCallback` (note that it is not always possible to detect these, especially where the client has not disconnected cleanly) 

#### 8.2.2 CompletionStage

- [x] Return a `CompletionStage` to indicate async processing.
- [x] The correct entity provider is used.

### 8.3 EJB Resource Classes 

N/A

### 8.4 Client API 

N/A

## 9 Server-Sent Events

- [ ] Not yet implemented

## 10 Context

This applies to the types allowed using `@Context` in method parameters.

### 10.1 Concurrency 

- [x] All injected instances are threadsafe.

### 10.2 Context Types

This refers to the types of objects that are injectable as method parameters via
the `@Context` annotation.

Sections below describe the types required by the spec. Mu-Servers implementation also
allows injection of `MuRequest` and `MuResponse`.

#### 10.2.1 Application 

N/A. Will not implement, as there is no support for `Application`.

#### 10.2.2 URIs and URI Templates 

- [x] Implemented `@Context javax.ws.rs.core.UriInfo`

#### 10.2.3 Headers 

- [x] Implemented `@Context javax.ws.rs.core.HttpHeaders`

#### 10.2.4 Content Negotiation and Preconditions 

- [ ] Not yet implemented.

#### 10.2.5 Security Context 

- [x] Implemented.

#### 10.2.6 Providers 

Will not implement.

#### 10.2.7 Resource Context 

Will not implement as it violates principle of programmatic configuration. Users should pass any context 
needed into sub-resource constructors explicitly.

#### 10.2.8 Configuration 

Will not implement. Configuration should be handled by the user.

## 11 Environment

None of this section is applicable to MuServer as it does not manage your server's lifecycle.

## 12 Runtime Delegate

- [x] Implemented, although note that `createEndpoint` will never be supported.

## Interface implementations

The following are not described by the spec but are interfaces defined in the jax-rs spec API that need to be implemented.

- [x] `UriBilder`
- [ ] `Link`
- [ ] `VariantListBuilder`