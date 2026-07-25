package io.muserver.rest;

import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.NotFoundException;
import org.jspecify.annotations.Nullable;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ext.ExceptionMapper;

import java.util.List;
import java.util.Objects;

/**
 * Indicates that a JAX-RS URI parameter value could not be converted to the
 * Java type required by a resource method.
 *
 * <p>This exception is used for values obtained from {@link QueryParam},
 * {@link PathParam}, and {@link MatrixParam}. It extends
 * {@link NotFoundException} because Jakarta REST requires conversion failures
 * from these parameter sources to produce a {@code 404 Not Found} response.
 *
 * <p>Applications may register an
 * {@link ExceptionMapper} for {@code UriParameterConversionException} to customize the
 * response, for example to return a {@code 400 Bad Request} with an
 * application-specific validation message.
 *
 * <p>When Mu's built-in enum converter is used, {@link #getAllowedValues()}
 * contains the enum constant names accepted by that converter.
 *
 * <p>The original conversion failure is available from {@link #getCause()}.
 */
public final class UriParameterConversionException extends NotFoundException {
    private final String parameterName;
    private final @Nullable String parameterValue;
    private final Class<?> targetType;
    private final List<String> allowedValues;

    UriParameterConversionException(String parameterName, @Nullable String parameterValue, Class<?> targetType, List<String> allowedValues, Throwable cause) {
        super("Could not convert URI parameter \"" + parameterName + "\" with value \"" + parameterValue + "\" to " + targetType.getTypeName(), cause);
        this.parameterName = Objects.requireNonNull(parameterName, "parameterName");
        this.parameterValue = parameterValue;
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
    }

    /**
     * @return the name declared by the parameter annotation
     */
    public String getParameterName() {
        return parameterName;
    }

    /**
     * @return the supplied parameter value, or {@code null} if no value was supplied
     */
    public @Nullable String getParameterValue() {
        return parameterValue;
    }

    /**
     * @return the Java type required by the resource method
     */
    public Class<?> getTargetType() {
        return targetType;
    }

    /**
     * Returns the values accepted by the parameter converter when they are known exactly.
     *
     * @return the allowed values, or an empty list when the converter does not declare a finite set
     */
    public List<String> getAllowedValues() {
        return allowedValues;
    }
}
