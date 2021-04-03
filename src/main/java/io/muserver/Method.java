package io.muserver;

/**
 * An HTTP Method
 */
public enum Method {

    /**
     * The GET HTTP method
     */
    GET,
    /**
     * The POST HTTP method
     */
    POST,
    /**
     * The HEAD HTTP method
     */
    HEAD,
    /**
     * The OPTIONS HTTP method
     */
    OPTIONS,
    /**
     * The PUT HTTP method
     */
    PUT,
    /**
     * The DELETE HTTP method
     */
    DELETE,
    /**
     * The TRACE HTTP method
     */
    TRACE,
    /**
     * The CONNECT HTTP method
     */
    CONNECT,
    /**
     * The PATCH HTTP method
     */
    PATCH;

    static Method fromNetty(io.netty.handler.codec.http.HttpMethod method) {
        return Method.valueOf(method.name());
    }

}
