package io.muserver;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.List;

class WebApplicationExceptionConverter {
    static WebApplicationException toJakarta(javax.ws.rs.WebApplicationException original) {
        if (original == null) return null;

        javax.ws.rs.core.Response oldResp = original.getResponse();
        Response.ResponseBuilder rb = Response.status(oldResp.getStatus());
        if (oldResp.hasEntity()) {
            rb.entity(oldResp.getEntity());
        }
        for (String headerName : oldResp.getHeaders().keySet()) {
            List<Object> headerValues = oldResp.getHeaders().get(headerName);
            for (Object headerValue : headerValues) {
                rb.header(headerName, headerValue);
            }
        }
        Response convertedResponse = rb.build();
        if (original instanceof javax.ws.rs.BadRequestException) { return new jakarta.ws.rs.BadRequestException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.ForbiddenException) { return new jakarta.ws.rs.ForbiddenException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.NotAcceptableException) { return new jakarta.ws.rs.NotAcceptableException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.NotAllowedException) { return new jakarta.ws.rs.NotAllowedException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.NotAuthorizedException) { return new jakarta.ws.rs.NotAuthorizedException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.NotFoundException) { return new jakarta.ws.rs.NotFoundException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.NotSupportedException) { return new jakarta.ws.rs.NotSupportedException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.ClientErrorException) { return new jakarta.ws.rs.ClientErrorException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.InternalServerErrorException) { return new jakarta.ws.rs.InternalServerErrorException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.RedirectionException) { return new jakarta.ws.rs.RedirectionException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.ServiceUnavailableException) { return new jakarta.ws.rs.ServiceUnavailableException(original.getMessage(), convertedResponse); }
        if (original instanceof javax.ws.rs.ServerErrorException) { return new jakarta.ws.rs.ServerErrorException(original.getMessage(), convertedResponse); }
        return new jakarta.ws.rs.WebApplicationException(original.getMessage(), convertedResponse);
    }
}
