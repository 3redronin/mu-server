package io.muserver.rest;

import io.muserver.*;
import io.muserver.openapi.ExternalDocumentationObject;
import io.muserver.openapi.ParameterObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.muserver.openapi.ParameterObjectBuilder.parameterObject;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObjectFrom;
import static java.util.Collections.emptyList;

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

    static ResourceMethodParam fromParameter(int index, Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders, UriPattern methodPattern) {

        Pattern pattern = null;
        ValueSource source = getSource(parameterHandle);
        boolean isRequired = source == ValueSource.PATH_PARAM || source == ValueSource.LEGACY_PATH_PARAM || hasDeclared(parameterHandle, Required.class);
        if (source == ValueSource.MESSAGE_BODY) {
            DescriptionData descriptionData = DescriptionData.fromAnnotation(parameterHandle, null);
            return new MessageBodyParam(index, source, parameterHandle, descriptionData, isRequired);
        } else if (source == ValueSource.CONTEXT) {
            return new ContextParam(index, source, parameterHandle);
        } else if (source == ValueSource.SUSPENDED) {
            return new SuspendedParam(index, source, parameterHandle);
        } else if (source.isLegacy) {
            boolean encodedRequested = hasDeclared(parameterHandle, javax.ws.rs.Encoded.class);
            boolean isDeprecated = hasDeclared(parameterHandle, Deprecated.class);
            ParamConverter<?> converter = getParamConverter(parameterHandle, paramConverterProviders);
            boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(javax.ws.rs.ext.ParamConverter.Lazy.class) != null;
            boolean explicitDefault = hasDeclared(parameterHandle, javax.ws.rs.DefaultValue.class);
            Object defaultValue = getDefaultValue(parameterHandle, converter, lazyDefaultValue);

            isRequired |= (!explicitDefault && parameterHandle.getType().isPrimitive());

            String key = source == ValueSource.LEGACY_COOKIE_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.CookieParam.class).value()
                : source == ValueSource.LEGACY_HEADER_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.HeaderParam.class).value()
                : source == ValueSource.LEGACY_MATRIX_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.MatrixParam.class).value()
                : source == ValueSource.LEGACY_FORM_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.FormParam.class).value()
                : source == ValueSource.LEGACY_PATH_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.PathParam.class).value()
                : source == ValueSource.LEGACY_QUERY_PARAM ? parameterHandle.getDeclaredAnnotation(javax.ws.rs.QueryParam.class).value()
                : "";
            if (key.length() == 0) {
                throw new WebApplicationException("No parameter specified for the " + source + " in " + parameterHandle);
            }
            if (source == ValueSource.LEGACY_PATH_PARAM && methodPattern != null) {
                String regex = methodPattern.regexFor(key);
                if (regex != null) {
                    pattern = Pattern.compile(regex);
                }
            }

            DescriptionData descriptionData = DescriptionData.fromAnnotation(parameterHandle, key);
            return new RequestBasedParam(index, source, parameterHandle, defaultValue, encodedRequested, lazyDefaultValue, converter, descriptionData, key, isDeprecated, isRequired, pattern, explicitDefault);
        } else {
            boolean encodedRequested = hasDeclared(parameterHandle, Encoded.class);
            boolean isDeprecated = hasDeclared(parameterHandle, Deprecated.class);
            ParamConverter<?> converter = getParamConverter(parameterHandle, paramConverterProviders);
            boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(ParamConverter.Lazy.class) != null;
            boolean explicitDefault = hasDeclared(parameterHandle, DefaultValue.class);
            Object defaultValue = getDefaultValue(parameterHandle, converter, lazyDefaultValue);

            isRequired |= (!explicitDefault && parameterHandle.getType().isPrimitive());

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
            if (source == ValueSource.PATH_PARAM && methodPattern != null) {
                String regex = methodPattern.regexFor(key);
                if (regex != null) {
                    pattern = Pattern.compile(regex);
                }
            }

            DescriptionData descriptionData = DescriptionData.fromAnnotation(parameterHandle, key);
            return new RequestBasedParam(index, source, parameterHandle, defaultValue, encodedRequested, lazyDefaultValue, converter, descriptionData, key, isDeprecated, isRequired, pattern, explicitDefault);
        }
    }

    static class RequestBasedParam extends ResourceMethodParam {

        private final Object defaultValue;
        final boolean encodedRequested;
        private final boolean lazyDefaultValue;
        private final ParamConverter paramConverter;
        final String key;
        final boolean isDeprecated;
        private final Pattern pattern;
        private final boolean explicitDefault;

        ParameterObjectBuilder createDocumentationBuilder() {
            ParameterObjectBuilder builder = parameterObject()
                .withIn(source.openAPIIn)
                .withRequired(isRequired)
                .withDeprecated(isDeprecated)
                .withName(key);
            ExternalDocumentationObject externalDoc = null;
            if (descriptionData != null) {
                String desc = descriptionData.summaryAndDescription();
                builder
                    .withDescription(key.equals(desc) ? null : desc)
                    .withExample(descriptionData.example);
                externalDoc = descriptionData.externalDocumentation;
            }
            Pattern patternIfNotDefault = this.pattern == null || UriPattern.DEFAULT_CAPTURING_GROUP_PATTERN.equals(this.pattern.pattern()) ? null : this.pattern;
            return builder.withSchema(
                schemaObjectFrom(parameterHandle.getType(), parameterHandle.getParameterizedType(), isRequired)
                    .withDefaultValue(source == ValueSource.PATH_PARAM || !hasExplicitDefault() ? null : defaultValue())
                    .withExternalDocs(externalDoc)
                    .withPattern(patternIfNotDefault)
                    .build()
            );
        }

        RequestBasedParam(int index, ValueSource source, Parameter parameterHandle, Object defaultValue, boolean encodedRequested, boolean lazyDefaultValue, ParamConverter paramConverter, DescriptionData descriptionData, String key, boolean isDeprecated, boolean isRequired, Pattern pattern, boolean explicitDefault) {
            super(index, source, parameterHandle, descriptionData, isRequired);
            this.defaultValue = defaultValue;
            this.encodedRequested = encodedRequested;
            this.lazyDefaultValue = lazyDefaultValue;
            this.paramConverter = paramConverter;
            this.key = key;
            this.isDeprecated = isDeprecated;
            this.pattern = pattern;
            this.explicitDefault = explicitDefault;
        }

        /**
         * @return True if the API author has explicitly set a default value for the param
         * using the {@link DefaultValue} annotation.
         */
        public boolean hasExplicitDefault() {
            return explicitDefault;
        }


        public Object defaultValue() {
            boolean skipConverter = defaultValue != null && !lazyDefaultValue;
            return convertValue(parameterHandle, paramConverter, skipConverter, defaultValue);
        }

        public Object getValue(JaxRSRequest jaxRequest, RequestMatcher.MatchedMethod matchedMethod, CollectionParameterStrategy cps) throws IOException {
            MuRequest muRequest = jaxRequest.muRequest;
            Class<?> paramClass = parameterHandle.getType();
            if (UploadedFile.class.isAssignableFrom(paramClass)) {
                return muRequest.uploadedFile(key);
            } else if (File.class.isAssignableFrom(paramClass)) {
                UploadedFile uf = muRequest.uploadedFile(key);
                return uf == null ? null : uf.asFile();
            } else if (Collection.class.isAssignableFrom(paramClass)) {
                Type t = parameterHandle.getParameterizedType();
                if (t instanceof ParameterizedType) {
                    Type[] actualTypeArguments = ((ParameterizedType) t).getActualTypeArguments();
                    if (actualTypeArguments.length == 1) {
                        Type argType = actualTypeArguments[0];
                        boolean isUploadedFileList = (argType instanceof Class<?> && UploadedFile.class.isAssignableFrom((Class<?>) argType));
                        if (!isUploadedFileList && argType instanceof WildcardType) {
                            WildcardType wt = (WildcardType) argType;
                            for (Type upperBound : wt.getUpperBounds()) {
                                if (upperBound instanceof Class<?> && UploadedFile.class.isAssignableFrom((Class<?>) upperBound)) {
                                    isUploadedFileList = true;
                                    break;
                                }
                            }
                        }
                        if (isUploadedFileList) {
                            List<UploadedFile> uploadedFiles = muRequest.uploadedFiles(key);
                            if (Set.class.isAssignableFrom(paramClass)) {
                                return new HashSet<>(uploadedFiles);
                            }
                            return uploadedFiles;
                        }
                    }
                }
            }

            if (paramClass.isAssignableFrom(PathSegment.class)) {
                PathSegment seg = matchedMethod.pathParams.get(key);
                if (seg != null && encodedRequested) {
                    return ((MuPathSegment) seg).toEncoded();
                }
                return seg;
            }else if (paramClass.isAssignableFrom(javax.ws.rs.core.PathSegment.class)) {
                PathSegment jakartaPS = matchedMethod.pathParams.get(key);
                javax.ws.rs.core.PathSegment seg = jakartaPS == null ? null : new LegacyMuPathSegment(jakartaPS.getPath(), new LegacyMultivaluedMapAdapter<>(jakartaPS.getMatrixParameters()));
                if (seg != null && encodedRequested) {
                    return ((LegacyMuPathSegment) seg).toEncoded();
                }
                return seg;
            } else if (paramClass.equals(Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key);
                return cookieValues.isEmpty() ? null : new Cookie(key, cookieValues.get(0));
            } else if (paramClass.equals(javax.ws.rs.core.Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key);
                return cookieValues.isEmpty() ? null : new javax.ws.rs.core.Cookie(key, cookieValues.get(0));
            } else if (paramClass.equals(io.muserver.Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key);
                return cookieValues.isEmpty() ? null : new CookieBuilder().withName(key).withValue(cookieValues.get(0)).build();
            }
            Collection<Object> collection = createCollection(paramClass);
            List<String> specifiedValue =
                source == ValueSource.PATH_PARAM || source == ValueSource.LEGACY_PATH_PARAM ? Collections.singletonList(matchedMethod.getPathParam(key))
                    : source == ValueSource.QUERY_PARAM || source == ValueSource.LEGACY_QUERY_PARAM ? getParamValues(jaxRequest.getUriInfo().getQueryParameters(), key, cps, collection != null)
                    : source == ValueSource.HEADER_PARAM || source == ValueSource.LEGACY_HEADER_PARAM ? getParamValues(jaxRequest.getHeaders(), key, cps, collection != null)
                    : source == ValueSource.FORM_PARAM || source == ValueSource.LEGACY_FORM_PARAM ? muRequest.form().getAll(key)
                    : source == ValueSource.COOKIE_PARAM || source == ValueSource.LEGACY_COOKIE_PARAM ? cookieValue(muRequest, key)
                    : source == ValueSource.MATRIX_PARAM || source == ValueSource.LEGACY_MATRIX_PARAM ? matrixParamValue(key, jaxRequest.relativePath())
                    : emptyList();
            boolean isSpecified = specifiedValue != null && !specifiedValue.isEmpty();
            if (encodedRequested && isSpecified) {
                specifiedValue = specifiedValue.stream().map(Mutils::urlEncode).collect(Collectors.toList());
            }
            if (collection != null) {
                if (isSpecified) {
                    for (String stringValue : specifiedValue) {
                        collection.add(ResourceMethodParam.convertValue(parameterHandle, paramConverter, false, stringValue));
                    }
                } else if (hasExplicitDefault()) {
                    collection.add(defaultValue());
                }
                return (collection instanceof List) ? Collections.unmodifiableList((List) collection)
                    : (collection instanceof SortedSet) ? Collections.unmodifiableSortedSet((SortedSet) collection)
                    : (collection instanceof Set) ? Collections.unmodifiableSet((Set) collection)
                    : Collections.unmodifiableCollection(collection);
            } else {
                return isSpecified ? ResourceMethodParam.convertValue(parameterHandle, paramConverter, false, specifiedValue.get(0)) : defaultValue();
            }
        }

        private List<String> getParamValues(MultivaluedMap<String, String> queryParameters, String key, CollectionParameterStrategy cps, boolean isCollectionType) {
            List<String> values = queryParameters.get(key);
            if (isCollectionType && values != null && cps == CollectionParameterStrategy.SPLIT_ON_COMMA) {
                List<String> copy = new ArrayList<>(values.size());
                for (String value : values) {
                    value = value.trim();
                    if (value.contains(",")) {
                        String[] bits = value.split("\\s*,\\s*");
                        Collections.addAll(copy, bits);
                    } else if (!value.isEmpty()) {
                        copy.add(value.trim());
                    }
                }
                return copy;
            }
            return values;
        }

        private List<String> cookieValue(MuRequest request, String key) {
            Optional<String> cookie = request.cookie(key);
            return cookie.map(Collections::singletonList).orElse(emptyList());
        }

        private List<String> matrixParamValue(String key, String path) {
            MuPathSegment last = MuUriInfo.pathStringToSegments(path, false).reduce((first, second) -> second).orElse(null);
            if (last != null && last.getMatrixParameters().containsKey(key)) {
                return last.getMatrixParameters().get(key);
            }
            return emptyList();
        }

        static Collection<Object> createCollection(Class<?> collectionType) {
            if (SortedSet.class.equals(collectionType)) {
                return new TreeSet<>();
            } else if (Set.class.equals(collectionType)) {
                return new HashSet<>();
            } else if (List.class.equals(collectionType) || Collection.class.equals(collectionType)) {
                return new ArrayList<>();
            } else {
                return null;
            }
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
            : hasDeclared(p, javax.ws.rs.MatrixParam.class) ? ValueSource.LEGACY_MATRIX_PARAM
            : hasDeclared(p, javax.ws.rs.QueryParam.class) ? ValueSource.LEGACY_QUERY_PARAM
            : hasDeclared(p, javax.ws.rs.FormParam.class) ? ValueSource.LEGACY_FORM_PARAM
            : hasDeclared(p, javax.ws.rs.PathParam.class) ? ValueSource.LEGACY_PATH_PARAM
            : hasDeclared(p, javax.ws.rs.CookieParam.class) ? ValueSource.LEGACY_COOKIE_PARAM
            : hasDeclared(p, javax.ws.rs.HeaderParam.class) ? ValueSource.LEGACY_HEADER_PARAM
            : hasDeclared(p, javax.ws.rs.core.Context.class) ? ValueSource.CONTEXT
            : hasDeclared(p, javax.ws.rs.container.Suspended.class) ? ValueSource.SUSPENDED
            : ValueSource.MESSAGE_BODY;

    }

    private static boolean hasDeclared(Parameter parameterHandle, Class<? extends Annotation> annotationClass) {
        return parameterHandle.getDeclaredAnnotation(annotationClass) != null;
    }

    private static ParamConverter<?> getParamConverter(Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders) {
        Class<?> paramType = parameterHandle.getType();
        Type parameterizedType = parameterHandle.getParameterizedType();
        if (Collection.class.isAssignableFrom(paramType) && parameterizedType instanceof ParameterizedType) {
            Type possiblyWildcardType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            Type type = (possiblyWildcardType instanceof WildcardType) ? ((WildcardType) possiblyWildcardType).getUpperBounds()[0] : possiblyWildcardType;
            if (type instanceof Class) {
                paramType = (Class<?>) type;
            }
        }
        Annotation[] declaredAnnotations = parameterHandle.getDeclaredAnnotations();
        for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
            ParamConverter<?> converter = paramConverterProvider.getConverter(paramType, parameterizedType, declaredAnnotations);
            if (converter == null && RequestBasedParam.createCollection(paramType) != null && parameterizedType instanceof ParameterizedType) {
                // Things like List<A> can be converted with just an 'A' param converter, so let's see if we have that
                ParameterizedType pt = (ParameterizedType) parameterizedType;
                Type[] ata = pt.getActualTypeArguments();
                if (ata.length == 1 && ata[0] instanceof ParameterizedType) {
                    ParameterizedType type = (ParameterizedType) ata[0];
                    Type rawType = type.getRawType();
                    if (rawType instanceof Class) {
                        converter = paramConverterProvider.getConverter((Class) rawType, type, declaredAnnotations);
                    }
                }
            }
            if (converter != null) return converter;
        }
        throw new MuException("Could not find a suitable ParamConverter for " + parameterizedType + " at " + parameterHandle.getDeclaringExecutable());
    }

    private static Object getDefaultValue(Parameter parameterHandle, ParamConverter<?> converter, boolean lazyDefaultValue) {
        String annotationValue = null;
        DefaultValue annotation = parameterHandle.getDeclaredAnnotation(DefaultValue.class);
        if (annotation != null) {
            annotationValue = annotation.value();
        } else {
            javax.ws.rs.DefaultValue oldAnnotation = parameterHandle.getDeclaredAnnotation(javax.ws.rs.DefaultValue.class);
            if (oldAnnotation != null) {
                annotationValue = oldAnnotation.value();
            }
        }
        if (annotationValue == null) {
            return converter instanceof HasDefaultValue ? ((HasDefaultValue) converter).getDefault() : null;
        }
        return convertValue(parameterHandle, converter, lazyDefaultValue, annotationValue);
    }

    private static Object convertValue(Parameter parameterHandle, ParamConverter<?> converter, boolean skipConverter, Object value) {
        if (converter == null || skipConverter) {
            return value;
        } else {
            try {
                // the value is only a non-string if a DefaultValue was specified which was converted to a non-string value already, in which case skipConverter is true
                String valueAsString = (String) value;
                if (value != null) {
                    return converter.fromString(valueAsString);
                }
                return converter instanceof HasDefaultValue
                    ? ((HasDefaultValue) converter).getDefault()
                    : null;
            } catch (Exception e) {
                throw new BadRequestException("Could not convert String value \"" + value + "\" to a " + parameterHandle.getType() + " using " + converter + " on parameter " + parameterHandle, e);
            }
        }
    }

    enum ValueSource {
        MESSAGE_BODY(null, false),
        QUERY_PARAM("query", false), MATRIX_PARAM(null, false), PATH_PARAM("path", false), COOKIE_PARAM("cookie", false), HEADER_PARAM("header", false), FORM_PARAM(null, false),
        LEGACY_QUERY_PARAM("query", true), LEGACY_MATRIX_PARAM(null, true), LEGACY_PATH_PARAM("path", true), LEGACY_COOKIE_PARAM("cookie", true), LEGACY_HEADER_PARAM("header", true), LEGACY_FORM_PARAM(null, true),
        CONTEXT(null, false), SUSPENDED(null, false);

        final String openAPIIn;
        final boolean isLegacy;

        ValueSource(String openAPIIn, boolean isLegacy) {
            this.openAPIIn = openAPIIn;
            this.isLegacy = isLegacy;
        }
    }

    interface HasDefaultValue {
        Object getDefault();
    }

}
