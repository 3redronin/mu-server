package io.muserver.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class CustomExceptionMapper {
    private static final Logger log = LoggerFactory.getLogger(CustomExceptionMapper.class);

    private final Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> mappers;

    CustomExceptionMapper(Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> mappers) {
        this.mappers = new HashMap<>(mappers);
    }

    @SuppressWarnings("unchecked")
    Response toResponse(Throwable ex) {

        Class<? extends Throwable> exClass = ex.getClass();

        int maxDepth = Integer.MAX_VALUE;
        ExceptionMapper exceptionMapper = findBestMatchingExceptionMapper(exClass, maxDepth);

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

    @SuppressWarnings("unchecked")
    private ExceptionMapper findBestMatchingExceptionMapper(Class<? extends Throwable> exClass, int maxDepth) {
        ExceptionMapper exceptionMapper = null;
        for (Map.Entry<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> entry : mappers.entrySet()) {
            Class mapperClass = entry.getKey();
            if (mapperClass.isAssignableFrom(exClass)) {
                int depth = 0;
                Class yo = exClass;
                while (yo != null) {
                    if (mapperClass.equals(yo)) {
                        if (depth < maxDepth) {
                            maxDepth = depth;
                            exceptionMapper = entry.getValue();
                        }
                        break;
                    }
                    depth++;
                    yo = yo.getSuperclass();
                }
            }
        }
        return exceptionMapper;
    }

}
