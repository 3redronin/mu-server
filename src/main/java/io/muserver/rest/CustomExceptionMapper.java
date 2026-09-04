package io.muserver.rest;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

class CustomExceptionMapper {
    private static final Logger log = LoggerFactory.getLogger(CustomExceptionMapper.class);

    private final Providers providers;

    CustomExceptionMapper(Providers providers) {
        this.providers = providers;
    }

    @SuppressWarnings("unchecked")
    @Nullable Response toResponse(Throwable ex) {

        if (ex instanceof InvocationTargetException && ex.getCause() != null) {
            ex = ex.getCause();
        }

        Class<? extends Throwable> exClass = ex.getClass();

        ExceptionMapper exceptionMapper = providers.getExceptionMapper(exClass);

        if (exceptionMapper == null) {
            return null;
        }

        try {
            Response response = exceptionMapper.toResponse(ex);
            if (response == null) {
                response = Response.noContent().build();
            }
            return response;
        } catch (Exception e) {
            String errorID = UUID.randomUUID().toString();
            log.error("Error thrown from exception mapper " + exceptionMapper + " so returning error to client with ErrorID=" + errorID, e);
            return Response.serverError()
                .type(MediaType.TEXT_HTML_TYPE)
                .entity("<h1>500 Internal Server Error</h1><p>ErrorID=" + errorID + "</p>")
                .build();
        }
    }

}
