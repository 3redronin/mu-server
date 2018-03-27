package io.muserver.rest;

import io.muserver.MuRequest;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.ParamConverter;
import javax.ws.rs.ext.ParamConverterProvider;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static io.muserver.Mutils.urlEncode;

abstract class ResourceMethodParam {

    final int index;
    protected final Parameter parameterHandle;
    final ValueSource source;

    ResourceMethodParam(int index, ValueSource source, Parameter parameterHandle) {
        this.index = index;
        this.source = source;
        this.parameterHandle = parameterHandle;
    }



    public static ResourceMethodParam fromParameter(int index, java.lang.reflect.Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders) {

        ValueSource source = getSource(parameterHandle);
        if (source == ValueSource.MESSAGE_BODY) {
            return new MessageBodyParam(index, source, parameterHandle);
        } else if (source == ValueSource.CONTEXT) {
            return new ContextParam(index, source, parameterHandle);
        } else {
            boolean encodedRequested = hasDeclared(parameterHandle, Encoded.class);
            ParamConverter<?> converter = getParamConverter(parameterHandle, paramConverterProviders);
            boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(ParamConverter.Lazy.class) != null;
            Object defaultValue = getDefaultValue(parameterHandle, converter, lazyDefaultValue);
            return new RequestBasedParam(index, source, parameterHandle, defaultValue, encodedRequested, lazyDefaultValue, converter);
        }
    }

    static class RequestBasedParam extends ResourceMethodParam {

        private final Object defaultValue;
        final boolean encodedRequested;
        private final boolean lazyDefaultValue;
        private final ParamConverter paramConverter;
        final String key;

        RequestBasedParam(int index, ValueSource source, Parameter parameterHandle, Object defaultValue, boolean encodedRequested, boolean lazyDefaultValue, ParamConverter paramConverter) {
            super(index, source, parameterHandle);
            this.defaultValue = defaultValue;
            this.encodedRequested = encodedRequested;
            this.lazyDefaultValue = lazyDefaultValue;
            this.paramConverter = paramConverter;
            this.key = source == ValueSource.COOKIE_PARAM ? parameterHandle.getDeclaredAnnotation(CookieParam.class).value()
                : source == ValueSource.HEADER_PARAM ? parameterHandle.getDeclaredAnnotation(HeaderParam.class).value()
                : source == ValueSource.MATRIX_PARAM ? parameterHandle.getDeclaredAnnotation(MatrixParam.class).value()
                : source == ValueSource.FORM_PARAM ? parameterHandle.getDeclaredAnnotation(FormParam.class).value()
                : source == ValueSource.PATH_PARAM ? parameterHandle.getDeclaredAnnotation(PathParam.class).value()
                : source == ValueSource.QUERY_PARAM ? parameterHandle.getDeclaredAnnotation(QueryParam.class).value()
                : "";
            if (this.key.length() == 0) {
                throw new WebApplicationException("No parameter specified for the " + source + " in " + parameterHandle);
            }
        }

        public String jsonType() {
            Class<?> type = parameterHandle.getType();
            if (type.equals(boolean.class) || type.equals(Boolean.class)) {
                return "boolean";
            } else if (type.equals(int.class) || type.equals(Integer.class) || type.equals(long.class) || type.equals(Long.class)) {
                return "integer";
            } else if (type.isAssignableFrom(Number.class)) {
                return "number";
            } else if (type.isAssignableFrom(CharSequence.class) || type.equals(byte.class) || type.equals(Byte.class) || type.isAssignableFrom(Date.class)) {
                return "string";
            } else if (type.isAssignableFrom(Collection.class) || type.isArray()) {
                return "array";
            }
            return "object";
        }
        public String jsonFormat() {
            Class<?> type = parameterHandle.getType();
            if (type.equals(int.class) || type.equals(Integer.class)) {
                return "int32";
            } else if (type.equals(long.class) || type.equals(Long.class)) {
                return "int64";
            } else if (type.equals(float.class) || type.equals(Float.class)) {
                return "float";
            } else if (type.equals(double.class) || type.equals(Double.class)) {
                return "double";
            } else if (type.equals(byte.class) || type.equals(Byte.class)) {
                return "byte";
            } else if (type.equals(Date.class)) {
                return "date-time";
            }
            return null;
        }

        public Object defaultValue() {
            return convertValue(parameterHandle, paramConverter, !lazyDefaultValue, defaultValue);
        }

        public Object getValue(MuRequest request, RequestMatcher.MatchedMethod matchedMethod) throws IOException {
            String specifiedValue =
                source == ValueSource.COOKIE_PARAM ? request.cookie(key).orElse("") // TODO make request.cookie return a string and default it
                : source == ValueSource.HEADER_PARAM ? String.join(",", request.headers().getAll(key))
                : source == ValueSource.MATRIX_PARAM ? "" // TODO support matrix params
                : source == ValueSource.FORM_PARAM ? String.join(",", request.formValue(key))
                : source == ValueSource.PATH_PARAM ? matchedMethod.pathParams.get(key)
                : source == ValueSource.QUERY_PARAM ? String.join(",", request.parameters(key))
                : null;
            boolean isSpecified = specifiedValue != null && specifiedValue.length() > 0;
            if (isSpecified && encodedRequested) {
                specifiedValue = urlEncode(specifiedValue);
            }
            return isSpecified ? ResourceMethodParam.convertValue(parameterHandle, paramConverter, false, specifiedValue) : defaultValue();
        }
    }

    static class MessageBodyParam extends ResourceMethodParam {
        MessageBodyParam(int index, ValueSource source, Parameter parameterHandle) {
            super(index, source, parameterHandle);
        }
    }

    static class ContextParam extends ResourceMethodParam {
        ContextParam(int index, ValueSource source, Parameter parameterHandle) {
            super(index, source, parameterHandle);
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
        MESSAGE_BODY(null), QUERY_PARAM("query"), MATRIX_PARAM(null), PATH_PARAM("path"), COOKIE_PARAM("cookie"), HEADER_PARAM("header"), FORM_PARAM(null), CONTEXT(null);

        final String openAPIIn;

        ValueSource(String openAPIIn) {
            this.openAPIIn = openAPIIn;
        }
    }

    interface HasDefaultValue {
        Object getDefault();
    }

}
