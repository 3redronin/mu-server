
# Implementation of spec

This section goes into detail about which parts of the spec are implemented, which parts may be implemented
later, and which parts will never be implemented. The numbers and headers are taken directly from the
[jaxrs-2.0 final spec](http://download.oracle.com/otn-pub/jcp/jaxrs-2_0-fr-eval-spec/jsr339-jaxrs-2.0-final-spec.pdf)
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

#### 3.3.3 Return Type 

- [x] void methods
- [x] Response objects
- [x] GenericEntity objects
- [x] Arbitrary objects (where supported by MessageBodyWriters)

#### 3.3.4 Exceptions 

- [ ] Partially implemented, but requires tests on sub-parts 1 through 4. Does not re-process exceptions.

#### 3.3.5 HEAD and OPTIONS 

- [ ] Not yet implemented.

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

### 4.2 Entity Providers 

- [x] Implemented.

#### 4.2.1 Message Body Reader 

- [x] Implemented.

#### 4.2.2 Message Body Writer 

- [x] Implemented.

#### 4.2.3 Declaring Media Type Capabilities 

- [x] Implemented.

#### 4.2.4 Standard Entity Providers 

- [x] Non-XML types Implemented
- [ ] Source and JAXB XML support.

#### 4.2.5 Transfer Encoding 

- [x] Implemented.

#### 4.2.6 Content Encoding 

- [x] Implemented.

### 4.3 Context Providers 

- [ ] Not implemented.

#### 4.3.1 Declaring Media Type Capabilities 

- [ ] Not implemented.

### 4.4 Exception Mapping Providers 

- [ ] Not implemented.

### 4.5 Exceptions 

#### 4.5.1 Server Runtime 

- [ ] Not implemented.

#### 4.5.2 Client Runtime 

N/A as Mu does not support Client Runtime.

## 5 Client API

N/A. This is a server-only implementation and there is no plan for a Client implementation.

## 6 Filters and Interceptors

- [ ] Not yet implemented.

### 6.1 Introduction 

- [ ] Not yet implemented.

### 6.2 Filters 

- [ ] Not yet implemented.

### 6.3 Entity Interceptors 

- [ ] Not yet implemented.

### 6.4 Lifecycle 

- [ ] Not yet implemented.

### 6.5 Binding 

- [ ] Not yet implemented.

#### 6.5.1 Global Binding 

- [ ] Not yet implemented.

#### 6.5.2 Name Binding 

- [ ] Not yet implemented.

#### 6.5.3 Dynamic Binding 

- [ ] Not yet implemented.

#### 6.5.4 Binding in Client API 

N/A

### 6.6 Priorities 

- [ ] Not yet implemented.

### 6.7 Exceptions 

- [ ] Not yet implemented.

#### 6.7.1 Server Runtime 

- [ ] Not yet implemented.

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

- [ ] Not yet implemented.

### 8.1 Introduction 

- [ ] Not yet implemented.

### 8.2 Server API 

- [ ] Not yet implemented.

#### 8.2.1 Timeouts and Callbacks

- [ ] Not yet implemented. 

### 8.3 EJB Resource Classes 

N/A

### 8.4 Client API 

N/A

## 9 Context

- [ ] Not yet implemented.

### 9.1 Concurrency 

- [ ] Not yet implemented.

### 9.2 Context Types 

- [ ] Not yet implemented.

#### 9.2.1 Application 

N/A. Will not implement, as there is no support for `Application`.

#### 9.2.2 URIs and URI Templates 

- [ ] Not yet implemented.

#### 9.2.3 Headers 

- [ ] Not yet implemented.

#### 9.2.4 Content Negotiation and Preconditions 

- [ ] Not yet implemented.

#### 9.2.5 Security Context 

- [ ] Not yet implemented.

#### 9.2.6 Providers 

Will not implement.

#### 9.2.7 Resource Context 

Will not implement as it violates principle of programmatic configuration. Users should pass any context 
needed into sub-resource constructors explicitly.

#### 9.2.8 Configuration 

Will not implement. Configuration should be handled by the user.

## 10 Environment

None of this section is applicable to MuServer.

## 11 Runtime Delegate

N/A