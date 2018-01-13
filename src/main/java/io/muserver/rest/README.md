
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

Only singletons will ever be supported.

#### 3.1.2 Constructors 

There are no restrictions or requirements around constructors of your resources, as you instantiate your own
resource instances.

### 3.2 Fields and Bean Properties 

Not applicable as only singletons supported

### 3.3 Resource Methods 

Mostly implemented, except Resource Locators. See sub-sections for details. Note that only standard HTTP
headers are supported.

#### 3.3.1 Visibility 

Implemented, although Mu does not warn about mis-configured methods.

#### 3.3.2 Parameters 

Partially implemented. Not all ParamTypes yet supported. Encoded and Default not yet supported.
ApplicationPath: not yet
Path: done
PathParam: done
QueryParam: not yet
FormParam: not yet
MatrixParam: not yet
CookieParam: not yet
HeaderParam: not yet



#### 3.3.3 Return Type 

Implemented.

#### 3.3.4 Exceptions 

Partially implemented, but requires tests on sub-parts 1 through 4. Does not re-process exceptions.

#### 3.3.5 HEAD and OPTIONS 

Not yet implemented.

### 3.4 URI Templates 

Implemented.

#### 3.4.1 Sub Resources 

Sub Resources supported, but sub-resource locators not yet implemented.

### 3.5 Declaring Media Type Capabilities 

Implemented.

### 3.6 Annotation Inheritance 

Implemented.

### 3.7 Matching Requests to Resource Methods 

Implemented.

#### 3.7.1 Request Preprocessing 

Implemented.

#### 3.7.2 Request Matching 

Implemented.

#### 3.7.3 Converting URI Templates to Regular Expressions 

Implemented.

### 3.8 Determining the MediaType of Responses 

Implemented.

## 4 Providers

Implemented, aside from dependency injection and classpath scanning etc, which will never be implemented.

### 4.1 Lifecycle and Environment 

Only singletons supported.

#### 4.1.1 Automatic Discovery 

This will never be implemented. Users should explicitly and programmatically specify any providers using
the `io.muserver.rest.RestHandlerBuilder` class.

#### 4.1.2 Constructors 

N/A as Mu will never instantiate user classes.

### 4.2 Entity Providers 

Implemented.

#### 4.2.1 Message Body Reader 

Implemented.

#### 4.2.2 Message Body Writer 

Implemented.

#### 4.2.3 Declaring Media Type Capabilities 

Implemented.

#### 4.2.4 Standard Entity Providers 

Implemented, except for Source and JAXB XML support.

#### 4.2.5 Transfer Encoding 

Implemented.

#### 4.2.6 Content Encoding 

Implemented.

### 4.3 Context Providers 

Not implemented.

#### 4.3.1 Declaring Media Type Capabilities 

Not implemented.

### 4.4 Exception Mapping Providers 

Not implemented.

### 4.5 Exceptions 

#### 4.5.1 Server Runtime 

Not implemented.

#### 4.5.2 Client Runtime 

N/A as Mu does not support Client Runtime.

## 5 Client API

Not implemented. This is a server-only implementation and there is no plan for a Client implementation.

## 6 Filters and Interceptors

Not yet implemented.

### 6.1 Introduction 

Not yet implemented.

### 6.2 Filters 

Not yet implemented.

### 6.3 Entity Interceptors 

Not yet implemented.

### 6.4 Lifecycle 

Not yet implemented.

### 6.5 Binding 

Not yet implemented.

#### 6.5.1 Global Binding 

Not yet implemented.

#### 6.5.2 Name Binding 

Not yet implemented.

#### 6.5.3 Dynamic Binding 

Not yet implemented.

#### 6.5.4 Binding in Client API 

N/A

### 6.6 Priorities 

Not yet implemented.

### 6.7 Exceptions 

Not yet implemented.

#### 6.7.1 Server Runtime 

Not yet implemented.

#### 6.7.2 Client Runtime 

N/A

## 7 Validation

Not yet implemented.

### 7.1 Constraint Annotations 

Not yet implemented.

### 7.2 Annotations and Validators 

Not yet implemented.

### 7.3 Entity Validation 

Not yet implemented.

### 7.4 Default Validation Mode 

Not yet implemented.

### 7.5 Annotation Inheritance 

Not yet implemented.

### 7.6 Validation and Error Reporting 

Not yet implemented.

## 8 Asynchronous Processing

Not yet implemented.

### 8.1 Introduction 

Not yet implemented.

### 8.2 Server API 

Not yet implemented.

#### 8.2.1 Timeouts and Callbacks

Not yet implemented. 

### 8.3 EJB Resource Classes 

N/A

### 8.4 Client API 

N/A

## 9 Context

Not yet implemented.

### 9.1 Concurrency 

Not yet implemented.

### 9.2 Context Types 

Not yet implemented.

#### 9.2.1 Application 

Will not implement, as there is no support for `Application`.

#### 9.2.2 URIs and URI Templates 

Not yet implemented.

#### 9.2.3 Headers 

Not yet implemented.

#### 9.2.4 Content Negotiation and Preconditions 

Not yet implemented.

#### 9.2.5 Security Context 

Not yet implemented.

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