package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.openapi.ParameterObjectBuilder;

import javax.ws.rs.*;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;

import static io.muserver.Mutils.urlEncode;
import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;

abstract class ResourceMethodParam {

    final int index;
    final Parameter parameterHandle;
    final ValueSource source;
    final DescriptionData descriptionData;
    final boolean isRequired;

    ResourceMethodParam(int index, ValueSource source, Parameter parameterHandle, DescriptionData descriptionData, boolean isRequired) {
        this.index = index;
        this.source = source;
        this.parameterHandle = parameterHandle;
        this.descriptionData = descriptionData;
        this.isRequired = isRequired;
    }

    static ResourceMethodParam fromParameter(int index, java.lang.reflect.Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders) {

        ValueSource source = getSource(parameterHandle);
        boolean isRequired = source == ValueSource.PATH_PARAM || hasDeclared(parameterHandle, Required.class);
        if (source == ValueSource.MESSAGE_BODY) {
            DescriptionData descriptionData = getDescriptionDataForParameter(parameterHandle, "requestBody");
            return new MessageBodyParam(index, source, parameterHandle, descriptionData, isRequired);
        } else if (source == ValueSource.CONTEXT) {
            return new ContextParam(index, source, parameterHandle);
        } else if (source == ValueSource.SUSPENDED) {
            return new SuspendedParam(index, source, parameterHandle);
        } else {
            boolean encodedRequested = hasDeclared(parameterHandle, Encoded.class);
            boolean isDeprecated = hasDeclared(parameterHandle, Deprecated.class);
            ParamConverter<?> converter = getParamConverter(parameterHandle, paramConverterProviders);
            boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(ParamConverter.Lazy.class) != null;
            Object defaultValue = getDefaultValue(parameterHandle, converter, lazyDefaultValue);

            String key = source == ValueSource.COOKIE_PARAM ? parameterHandle.getDeclaredAnnotation(CookieParam.class).value()
                : source == ValueSource.HEADER_PARAM ? parameterHandle.getDeclaredAnnotation(HeaderParam.class).value()
                : source == ValueSource.MATRIX_PARAM ? parameterHandle.getDeclaredAnnotation(MatrixParam.class).value()
                : source == ValueSource.FORM_PARAM ? parameterHandle.getDeclaredAnnotation(FormParam.class).value()
                : source == ValueSource.PATH_PARAM ? parameterHandle.getDeclaredAnnotation(PathParam.class).value()
                : source == ValueSource.QUERY_PARAM ? parameterHandle.getDeclaredAnnotation(QueryParam.class).value()
                : "";
            if (key.length() == 0) {
                throw new WebApplicationException("No parameter specified for the " + source + " in " + parameterHandle);
            }
            DescriptionData descriptionData = getDescriptionDataForParameter(parameterHandle, key);
            return new RequestBasedParam(index, source, parameterHandle, defaultValue, encodedRequested, lazyDefaultValue, converter, descriptionData, key, isDeprecated, isRequired);
        }
    }

    private static DescriptionData getDescriptionDataForParameter(Parameter parameterHandle, String key) {
        DescriptionData descriptionData = DescriptionData.fromAnnotation(parameterHandle, key);
        if (!key.equals(descriptionData.summary)) {
            String paramDesc = descriptionData.summary;
            if (descriptionData.description != null) {
                paramDesc += "\n" + descriptionData.description;
            }
            descriptionData = new DescriptionData(key, paramDesc, descriptionData.externalDocumentation, descriptionData.example);
        }
        return descriptionData;
    }

    static class RequestBasedParam extends ResourceMethodParam {

        private final Object defaultValue;
        final boolean encodedRequested;
        private final boolean lazyDefaultValue;
        private final ParamConverter paramConverter;
        final String key;
        final boolean isDeprecated;

        ParameterObjectBuilder createDocumentationBuilder() {
            ParameterObjectBuilder builder = parameterObject()
                .withIn(source.openAPIIn)
                .withRequired(isRequired)
                .withDeprecated(isDeprecated);
            if (descriptionData != null) {
                builder.withName(descriptionData.summary)
                    .withDescription(descriptionData.description)
                .withExample(descriptionData.example);
            }
            return builder.withSchema(
                schemaObjectFrom(parameterHandle.getType())
                    .withDefaultValue(defaultValue())
                    .build()
            );
        }

        RequestBasedParam(int index, ValueSource source, Parameter parameterHandle, Object defaultValue, boolean encodedRequested, boolean lazyDefaultValue, ParamConverter paramConverter, DescriptionData descriptionData, String key, boolean isDeprecated, boolean isRequired) {
            super(index, source, parameterHandle, descriptionData, isRequired);
            this.defaultValue = defaultValue;
            this.encodedRequested = encodedRequested;
            this.lazyDefaultValue = lazyDefaultValue;
            this.paramConverter = paramConverter;
            this.key = key;
            this.isDeprecated = isDeprecated;
        }

        public Object defaultValue() {
            return convertValue(parameterHandle, paramConverter, !lazyDefaultValue, defaultValue);
        }

        public Object getValue(MuRequest request, RequestMatcher.MatchedMethod matchedMethod) throws IOException {
            String specifiedValue =
                source == ValueSource.COOKIE_PARAM ? request.cookie(key).orElse("") // TODO make request.cookie return a string and default it
                    : source == ValueSource.HEADER_PARAM ? String.join(",", request.headers().getAll(key))
                    : source == ValueSource.MATRIX_PARAM ? "" // TODO support matrix params
                    : source == ValueSource.FORM_PARAM ? String.join(",", request.form().get(key))
                    : source == ValueSource.PATH_PARAM ? matchedMethod.pathParams.get(key)
                    : source == ValueSource.QUERY_PARAM ? String.join(",", request.query().getAll(key))
                    : null;
            boolean isSpecified = specifiedValue != null && specifiedValue.length() > 0;
            if (isSpecified && encodedRequested) {
                specifiedValue = urlEncode(specifiedValue);
            }
            return isSpecified ? ResourceMethodParam.convertValue(parameterHandle, paramConverter, false, specifiedValue) : defaultValue();
        }
    }

    static class MessageBodyParam extends ResourceMethodParam {
        MessageBodyParam(int index, ValueSource source, Parameter parameterHandle, DescriptionData descriptionData, boolean isRequired) {
            super(index, source, parameterHandle, descriptionData, isRequired);
        }
    }

    static class ContextParam extends ResourceMethodParam {
        ContextParam(int index, ValueSource source, Parameter parameterHandle) {
            super(index, source, parameterHandle, null, true);
        }
    }

    static class SuspendedParam extends ResourceMethodParam {
        SuspendedParam(int index, ValueSource source, Parameter parameterHandle) {
            super(index, source, parameterHandle, null, true);
        }
    }

    private static ValueSource getSource(Parameter p) {
        return hasDeclared(p, MatrixParam.class) ? ValueSource.MATRIX_PARAM
            : hasDeclared(p, QueryParam.class) ? ValueSource.QUERY_PARAM
            : hasDeclared(p, FormParam.class) ? ValueSource.FORM_PARAM
            : hasDeclared(p, PathParam.class) ? ValueSource.PATH_PARAM
            : hasDeclared(p, CookieParam.class) ? ValueSource.COOKIE_PARAM
            : hasDeclared(p, HeaderParam.class) ? ValueSource.HEADER_PARAM
            : hasDeclared(p, Context.class) ? ValueSource.CONTEXT
            : hasDeclared(p, Suspended.class) ? ValueSource.SUSPENDED
            : ValueSource.MESSAGE_BODY;

    }

    private static boolean hasDeclared(Parameter parameterHandle, Class<? extends Annotation> annotationClass) {
        return parameterHandle.getDeclaredAnnotation(annotationClass) != null;
    }

    private static ParamConverter<?> getParamConverter(Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders) {
        Class<?> paramType = parameterHandle.getType();
        Type parameterizedType = parameterHandle.getParameterizedType();
        Annotation[] declaredAnnotations = parameterHandle.getDeclaredAnnotations();
        for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
            ParamConverter<?> converter = paramConverterProvider.getConverter(paramType, parameterizedType, declaredAnnotations);
            if (converter != null) {
                return converter;
            }
        }
        throw new WebApplicationException("Could not find a suitable ParamConverter for " + paramType);
    }

    private static Object getDefaultValue(Parameter parameterHandle, ParamConverter<?> converter, boolean lazyDefaultValue) {
        DefaultValue annotation = parameterHandle.getDeclaredAnnotation(DefaultValue.class);
        if (annotation == null) {
            return converter instanceof HasDefaultValue ? ((HasDefaultValue) converter).getDefault() : null;
        }
        return convertValue(parameterHandle, converter, lazyDefaultValue, annotation.value());
    }

    private static Object convertValue(Parameter parameterHandle, ParamConverter<?> converter, boolean skipConverter, Object value) {
        if (converter == null || skipConverter) {
            return value;
        } else {
            try {
                String valueAsString = (String) value;
                return converter instanceof HasDefaultValue && valueAsString.isEmpty()
                    ? ((HasDefaultValue) converter).getDefault()
                    : converter.fromString(valueAsString);
            } catch (Exception e) {
                throw new BadRequestException("Could not convert String value \"" + value + "\" to a " + parameterHandle.getType() + " using " + converter + " on parameter " + parameterHandle, e);
            }
        }
    }

    enum ValueSource {
        MESSAGE_BODY(null), QUERY_PARAM("query"), MATRIX_PARAM(null), PATH_PARAM("path"), COOKIE_PARAM("cookie"), HEADER_PARAM("header"), FORM_PARAM(null), CONTEXT(null), SUSPENDED(null);

        final String openAPIIn;

        ValueSource(String openAPIIn) {
            this.openAPIIn = openAPIIn;
        }
    }

    interface HasDefaultValue {
        Object getDefault();
    }

}
