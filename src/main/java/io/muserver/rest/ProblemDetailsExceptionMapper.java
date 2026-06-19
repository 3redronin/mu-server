package io.muserver.rest;

import io.muserver.Mutils;
import io.muserver.openapi.Jsonizer;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link ExceptionMapper} that serializes exceptions as RFC 9457
 * <a href="https://www.rfc-editor.org/rfc/rfc9457">problem-details JSON</a>.
 * <p>
 * Problem details provide a standard, machine-readable error shape for HTTP APIs so clients can parse failures in a
 * consistent way. Instead of endpoint-specific error JSON, this mapper emits the standard fields {@code type},
 * {@code title}, {@code status}, {@code detail}, and {@code instance}, plus any extension members.
 * </p>
 * <p>
 * This mapper handles:
 * </p>
 * <ul>
 *     <li>{@link ProblemDetailsException}: values are taken from the exception.</li>
 *     <li>{@link WebApplicationException} with no response entity and a 4xx/5xx status: a problem-details body is created.</li>
 *     <li>Any other exception: a generic 500 problem-details response is created.</li>
 * </ul>
 * <p>
 * An {@code instance} URI is always included so a single failure can be correlated in logs and
 * support workflows.
 * </p>
 * <p>
 * Register this mapper on the REST handler using
 * {@link ProblemDetailsExceptionMapperBuilder#problemDetailsExceptionMapper()} to enable this behavior for JAX-RS
 * resource method execution.
 * </p>
 */
public class ProblemDetailsExceptionMapper <E extends Throwable> implements ExceptionMapper<E> {
    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionMapper.class);
    private static final MediaType APPLICATION_PROBLEM_JSON = MediaType.valueOf("application/problem+json");
    private final boolean log4xxProblemDetailsInstanceIds;
    private final boolean log5xxProblemDetailsInstanceIds;

    ProblemDetailsExceptionMapper(boolean log4xxProblemDetailsInstanceIds, boolean log5xxProblemDetailsInstanceIds) {
        this.log4xxProblemDetailsInstanceIds = log4xxProblemDetailsInstanceIds;
        this.log5xxProblemDetailsInstanceIds = log5xxProblemDetailsInstanceIds;
    }

    @Override
    public Response toResponse(E exception) {
        if (exception instanceof ProblemDetailsException) {
            ProblemDetailsException problem = (ProblemDetailsException) exception;
            if (shouldLogInstance(problem.getStatus())) {
                log.error("Sending a problem details response with instance={}", problem.getInstance(), exception);
            }
            return toResponse(problem.getStatus(), problem.getTitle(), problem.getDetail(), problem.getType(), problem.getInstance(), problem.getExtensionMembers());
        }

        if (exception instanceof WebApplicationException) {
            WebApplicationException webApplicationException = (WebApplicationException) exception;
            Response response = webApplicationException.getResponse();
            if (response == null) {
                return serverError(exception);
            }
            if (response.getEntity() != null) {
                return response;
            }
            Response.Status.Family family = Response.Status.Family.familyOf(response.getStatus());
            if (family != Response.Status.Family.CLIENT_ERROR && family != Response.Status.Family.SERVER_ERROR) {
                return response;
            }

            URI instance = newInstance();
            if (shouldLogInstance(response.getStatus())) {
                log.error("Sending a problem details response with instance={}", instance, exception);
            }
            String detail = exception.getMessage();
            if (Mutils.nullOrEmpty(detail)) {
                detail = defaultTitle(response.getStatus());
            }
            return toResponse(response.getStatus(), defaultTitle(response.getStatus()), detail, null, instance, null);
        }

        return serverError(exception);
    }

    private Response serverError(Throwable exception) {
        URI instance = newInstance();
        if (log5xxProblemDetailsInstanceIds) {
            log.error("Sending a problem details response with instance={}", instance, exception);
        }
        return toResponse(500, defaultTitle(500), "An unexpected error occurred", null, instance, null);
    }

    private boolean shouldLogInstance(int status) {
        Response.Status.Family family = Response.Status.Family.familyOf(status);
        if (family == Response.Status.Family.CLIENT_ERROR) {
            return log4xxProblemDetailsInstanceIds;
        }
        if (family == Response.Status.Family.SERVER_ERROR) {
            return log5xxProblemDetailsInstanceIds;
        }
        return false;
    }

    private URI newInstance() {
        return URI.create("urn:uuid:" + UUID.randomUUID());
    }

    private static String defaultTitle(int status) {
        Response.Status statusInfo = Response.Status.fromStatusCode(status);
        if (statusInfo != null) {
            return statusInfo.getReasonPhrase();
        }
        switch (Response.Status.Family.familyOf(status)) {
            case CLIENT_ERROR:
                return "Client Error";
            case SERVER_ERROR:
                return "Internal Server Error";
            default:
                return "HTTP " + status;
        }
    }

    private static Response toResponse(int status, String title, String detail, URI type, URI instance, Map<String, Object> extensionMembers) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (type != null) {
            values.put("type", type);
        }
        values.put("title", title);
        values.put("status", status);
        if (detail != null) {
            values.put("detail", detail);
        }
        values.put("instance", instance);
        if (extensionMembers != null && !extensionMembers.isEmpty()) {
            values.putAll(extensionMembers);
        }

        StringWriter writer = new StringWriter();
        try {
            Jsonizer.writeObject(writer, values);
        } catch (IOException e) {
            throw new RuntimeException("Could not serialize problem details JSON", e);
        }
        return Response.status(status).type(APPLICATION_PROBLEM_JSON).entity(writer.toString()).build();
    }
}
