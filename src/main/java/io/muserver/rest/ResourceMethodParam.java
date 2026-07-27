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
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
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
import static java.util.Objects.requireNonNull;

abstract class ResourceMethodParam {

    private final Introspection introspection;
    final Annotation[] annotations;
    final @Nullable DescriptionData descriptionData;

    ResourceMethodParam(Introspection introspection, Annotation[] annotations) {
        this.introspection = introspection;
        this.annotations = annotations;
        this.descriptionData = introspection.descriptionData;
    }

    int index() {
        return introspection.index;
    }

    Parameter parameterHandle() {
        return introspection.parameterHandle;
    }

    Class<?> type() {
        return introspection.type;
    }

    Type genericType() {
        return introspection.genericType;
    }

    ValueSource source() {
        return introspection.source;
    }

    boolean isRequired() {
        return introspection.isRequired;
    }

    final Introspection introspection() {
        return introspection;
    }

    static ResourceMethodParam fromParameter(int index, Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders, @Nullable UriPattern methodPattern) {
        return fromParameter(index, parameterHandle, parameterHandle,
            parameterHandle.getDeclaringExecutable().getDeclaringClass(), paramConverterProviders, methodPattern);
    }

    static ResourceMethodParam fromParameter(int index, Parameter parameterHandle, Parameter annotationSource, Class<?> concreteClass,
                                             List<ParamConverterProvider> paramConverterProviders, @Nullable UriPattern methodPattern) {
        return introspect(index, parameterHandle, annotationSource, concreteClass, methodPattern)
            .bind(paramConverterProviders);
    }

    static Introspection introspect(int index, Parameter parameterHandle, Parameter annotationSource,
                                    Class<?> concreteClass, @Nullable UriPattern methodPattern) {
        ResolvedParameter parameter = new ResolvedParameter(parameterHandle, annotationSource, concreteClass);
        @Nullable Pattern pattern = null;
        ValueSource source = getSource(annotationSource);
        boolean requestBased = source != ValueSource.MESSAGE_BODY
            && source != ValueSource.CONTEXT
            && source != ValueSource.SUSPENDED;
        boolean isRequired = source == ValueSource.PATH_PARAM || hasDeclared(annotationSource, Required.class);
        boolean encodedRequested = requestBased && hasDeclared(annotationSource, Encoded.class);
        boolean isDeprecated = requestBased && hasDeclared(annotationSource, Deprecated.class);
        String key = requestBased ? parameterName(source, annotationSource) : "";
        boolean explicitDefault = requestBased && hasDeclared(annotationSource, DefaultValue.class);
        @Nullable String explicitDefaultValue = explicitDefault
            ? requireNonNull(annotationSource.getDeclaredAnnotation(DefaultValue.class)).value()
            : null;
        if (source == ValueSource.PATH_PARAM && methodPattern != null) {
            String regex = methodPattern.regexFor(key);
            if (regex != null) {
                pattern = Pattern.compile(regex);
            }
        }
        if (requestBased) {
            isRequired |= (!explicitDefault && parameter.type.isPrimitive());
        }
        @Nullable DescriptionData descriptionData = source == ValueSource.MESSAGE_BODY
            ? DescriptionData.fromAnnotation(annotationSource, null)
            : requestBased ? DescriptionData.fromAnnotation(annotationSource, key) : null;
        return new Introspection(index, source, parameter, isRequired, encodedRequested,
            isDeprecated, key, pattern, explicitDefault, explicitDefaultValue, descriptionData);
    }

    static final class Introspection {
        final int index;
        final ValueSource source;
        private final Parameter parameterHandle;
        private final Class<?> type;
        private final Type genericType;
        final List<Annotation> annotations;
        final boolean isRequired;
        private final boolean encodedRequested;
        private final boolean deprecated;
        private final String key;
        private final @Nullable Pattern pattern;
        private final boolean explicitDefault;
        private final @Nullable String explicitDefaultValue;
        private final @Nullable DescriptionData descriptionData;

        private Introspection(int index, ValueSource source, ResolvedParameter parameter,
                              boolean isRequired, boolean encodedRequested, boolean deprecated, String key,
                              @Nullable Pattern pattern, boolean explicitDefault,
                              @Nullable String explicitDefaultValue,
                              @Nullable DescriptionData descriptionData) {
            this.index = index;
            this.source = source;
            this.parameterHandle = parameter.handle;
            this.type = parameter.type;
            this.genericType = parameter.genericType;
            this.annotations = Collections.unmodifiableList(
                Arrays.asList(parameter.annotations.clone()));
            this.isRequired = isRequired;
            this.encodedRequested = encodedRequested;
            this.deprecated = deprecated;
            this.key = key;
            this.pattern = pattern;
            this.explicitDefault = explicitDefault;
            this.explicitDefaultValue = explicitDefaultValue;
            this.descriptionData = descriptionData;
        }

        ResourceMethodParam bind(List<ParamConverterProvider> paramConverterProviders) {
            Annotation[] boundAnnotations = annotations.toArray(new Annotation[0]);
            if (source == ValueSource.MESSAGE_BODY) {
                return new MessageBodyParam(this, boundAnnotations);
            } else if (source == ValueSource.CONTEXT) {
                return new ContextParam(this, boundAnnotations);
            } else if (source == ValueSource.SUSPENDED) {
                return new SuspendedParam(this, boundAnnotations);
            } else {
                if (type.isArray() && io.muserver.Cookie.class.isAssignableFrom(type.getComponentType())) {
                    throw new MuException("io.muserver.Cookie[] is not supported for Jakarta REST parameters. "
                        + "Use jakarta.ws.rs.core.Cookie[] with @CookieParam instead.");
                }
                ParamConverter<?> converter =
                    getParamConverter(this, boundAnnotations, paramConverterProviders);
                boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(ParamConverter.Lazy.class) != null;
                Class<?> convertedValueType = convertedValueType(this);
                @Nullable Object defaultValue = getDefaultValue(
                    this, convertedValueType, converter, lazyDefaultValue);

                if (key.length() == 0) {
                    throw new WebApplicationException(
                        "No parameter specified for the " + source + " in " + parameterHandle);
                }

                return new RequestBasedParam(this, boundAnnotations, defaultValue,
                    lazyDefaultValue, convertedValueType, converter);
            }
        }
    }

    private static final class ResolvedParameter {
        private final Parameter handle;
        private final Class<?> type;
        private final Type genericType;
        private final Annotation[] annotations;

        private ResolvedParameter(Parameter handle, Parameter annotationSource, Class<?> concreteClass) {
            this.handle = handle;
            Type resolvedType = GenericTypeResolver.resolve(handle.getParameterizedType(), concreteClass,
                handle.getDeclaringExecutable().getDeclaringClass());
            this.genericType = resolvedType == null ? handle.getParameterizedType() : resolvedType;
            Class<?> resolvedClass = GenericTypeResolver.rawClass(genericType);
            this.type = resolvedClass == null ? handle.getType() : resolvedClass;
            this.annotations = combinedAnnotations(handle, annotationSource);
        }

        private static Annotation[] combinedAnnotations(Parameter handle, Parameter annotationSource) {
            Map<Class<? extends Annotation>, Annotation> combined = new LinkedHashMap<>();
            for (Annotation annotation : handle.getDeclaredAnnotations()) {
                combined.put(annotation.annotationType(), annotation);
            }
            for (Annotation annotation : annotationSource.getDeclaredAnnotations()) {
                combined.putIfAbsent(annotation.annotationType(), annotation);
            }
            return combined.values().toArray(new Annotation[0]);
        }
    }

    private static String parameterName(ValueSource source, Parameter annotationSource) {
        return source == ValueSource.COOKIE_PARAM ? annotationSource.getDeclaredAnnotation(CookieParam.class).value()
            : source == ValueSource.HEADER_PARAM ? annotationSource.getDeclaredAnnotation(HeaderParam.class).value()
            : source == ValueSource.MATRIX_PARAM ? annotationSource.getDeclaredAnnotation(MatrixParam.class).value()
            : source == ValueSource.FORM_PARAM ? annotationSource.getDeclaredAnnotation(FormParam.class).value()
            : source == ValueSource.PATH_PARAM ? annotationSource.getDeclaredAnnotation(PathParam.class).value()
            : source == ValueSource.QUERY_PARAM ? annotationSource.getDeclaredAnnotation(QueryParam.class).value()
            : "";
    }

    static class RequestBasedParam extends ResourceMethodParam {

        private final @Nullable Object defaultValue;
        private final boolean lazyDefaultValue;
        private final boolean array;
        private final Class<?> convertedValueType;
        private final ParamConverter paramConverter;

        boolean encodedRequested() {
            return introspection().encodedRequested;
        }

        String key() {
            return introspection().key;
        }

        boolean deprecated() {
            return introspection().deprecated;
        }

        private @Nullable Pattern pattern() {
            return introspection().pattern;
        }

        ParameterObjectBuilder createDocumentationBuilder() {
            ParameterObjectBuilder builder = parameterObject()
                .withIn(requireNonNull(source().openAPIIn, "Parameter source is not represented as an OpenAPI parameter"))
                .withRequired(isRequired())
                .withDeprecated(deprecated() ? true : null)
                .withName(key());
            @Nullable ExternalDocumentationObject externalDoc = null;
            if (descriptionData != null) {
                String desc = descriptionData.summaryAndDescription();
                builder
                    .withDescription(key().equals(desc) ? null : desc)
                    .withExample(descriptionData.example);
                externalDoc = descriptionData.externalDocumentation;
            }
            @Nullable Pattern pattern = pattern();
            @Nullable Pattern patternIfNotDefault = pattern == null || UriPattern.DEFAULT_CAPTURING_GROUP_PATTERN.equals(pattern.pattern()) ? null : pattern;
            return builder.withSchema(
                schemaObjectFrom(type(), genericType(), isRequired())
                    .withDefaultValue(documentationDefaultValue())
                    .withExternalDocs(externalDoc)
                    .withPattern(patternIfNotDefault)
                    .build()
            );
        }

        private @Nullable Object documentationDefaultValue() {
            if (source() == ValueSource.PATH_PARAM || !hasExplicitDefault()) {
                return null;
            }
            if (!array) {
                return defaultValue();
            }
            return Collections.singletonList(defaultValue());
        }

        RequestBasedParam(Introspection introspection, Annotation[] annotations,
                          @Nullable Object defaultValue, boolean lazyDefaultValue,
                          Class<?> convertedValueType, ParamConverter paramConverter) {
            super(introspection, annotations);
            this.defaultValue = defaultValue;
            this.lazyDefaultValue = lazyDefaultValue;
            this.array = isSupportedArray(introspection);
            this.convertedValueType = convertedValueType;
            this.paramConverter = paramConverter;
        }

        /**
         * @return True if the API author has explicitly set a default value for the param
         * using the {@link DefaultValue} annotation.
         */
        public boolean hasExplicitDefault() {
            return introspection().explicitDefault;
        }


        public @Nullable Object defaultValue() {
            boolean skipConverter = defaultValue != null && !lazyDefaultValue;
            return convertValue(parameterHandle(), convertedValueType, paramConverter, skipConverter, defaultValue, source(), key());
        }

        public @Nullable Object getValue(JaxRSRequest jaxRequest, RequestMatcher.MatchedMethod matchedMethod, CollectionParameterStrategy cps) throws IOException {
            MuRequest muRequest = jaxRequest.muRequest;
            Class<?> paramClass = type();
            if (array && UploadedFile.class.isAssignableFrom(convertedValueType)) {
                List<UploadedFile> uploadedFiles = muRequest.uploadedFiles(key());
                Object uploadedFileArray = Array.newInstance(convertedValueType, uploadedFiles.size());
                for (int i = 0; i < uploadedFiles.size(); i++) {
                    Array.set(uploadedFileArray, i, uploadedFiles.get(i));
                }
                return uploadedFileArray;
            } else if (UploadedFile.class.isAssignableFrom(paramClass)) {
                return muRequest.uploadedFile(key());
            } else if (File.class.isAssignableFrom(paramClass)) {
                UploadedFile uf = muRequest.uploadedFile(key());
                return uf == null ? null : uf.asFile();
            } else if (Collection.class.isAssignableFrom(paramClass)) {
                Type t = genericType();
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
                            List<UploadedFile> uploadedFiles = muRequest.uploadedFiles(key());
                            if (Set.class.isAssignableFrom(paramClass)) {
                                return new HashSet<>(uploadedFiles);
                            }
                            return uploadedFiles;
                        }
                    }
                }
            }

            if (paramClass.equals(io.muserver.Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key());
                return cookieValues.isEmpty() ? null : new CookieBuilder()
                    .withName(key())
                    .withValue(cookieValues.get(0))
                    .build();
            }
            if (paramClass.isAssignableFrom(PathSegment.class)) {
                PathSegment seg = matchedMethod.pathParams.get(key());
                if (seg != null && encodedRequested()) {
                    return ((MuPathSegment) seg).toEncoded();
                }
                return seg;
            }
            Collection<Object> collection = createCollection(paramClass);
            if (collection != null && source() == ValueSource.PATH_PARAM && isPathSegmentCollection()) {
                List<PathSegment> pathSegments = matchedMethod.getPathSegments(key());
                if (pathSegments.isEmpty() && hasExplicitDefault()) {
                    collection.add(defaultValue());
                } else {
                    for (PathSegment segment : pathSegments) {
                        collection.add(encodedRequested() ? ((MuPathSegment) segment).toEncoded() : segment);
                    }
                }
                return readOnly(collection);
            }
            String pathParam = source() == ValueSource.PATH_PARAM ? matchedMethod.getPathParam(key()) : null;
            List<String> specifiedValue =
                source() == ValueSource.PATH_PARAM ? (collection == null
                    ? (pathParam == null ? emptyList() : Collections.singletonList(pathParam))
                    : matchedMethod.getPathParams(key()))
                    : source() == ValueSource.QUERY_PARAM ? getParamValues(jaxRequest.getUriInfo().getQueryParameters(), key(), cps, collection != null || array)
                    : source() == ValueSource.HEADER_PARAM ? getParamValues(jaxRequest.getHeaders(), key(), cps, collection != null || array)
                    : source() == ValueSource.FORM_PARAM ? muRequest.form().getAll(key())
                    : source() == ValueSource.COOKIE_PARAM ? cookieValue(muRequest, key())
                    : source() == ValueSource.MATRIX_PARAM ? matrixParamValue(key(), jaxRequest.relativePath())
                    : emptyList();
            boolean isSpecified = specifiedValue != null && !specifiedValue.isEmpty();
            if (encodedRequested() && isSpecified) {
                specifiedValue = requireNonNull(specifiedValue).stream()
                    .map(value -> source() == ValueSource.FORM_PARAM ? FormUrlEncoder.formUrlEncode(value) : Mutils.urlEncode(value))
                    .collect(Collectors.toList());
            }
            if (array) {
                int size = isSpecified ? requireNonNull(specifiedValue).size() : hasExplicitDefault() ? 1 : 0;
                Object array = Array.newInstance(convertedValueType, size);
                if (isSpecified) {
                    for (int i = 0; i < size; i++) {
                        Object converted = ResourceMethodParam.convertValue(parameterHandle(), convertedValueType,
                            paramConverter, false, requireNonNull(specifiedValue).get(i), source(), key());
                        Array.set(array, i, converted);
                    }
                } else if (hasExplicitDefault()) {
                    Array.set(array, 0, defaultValue());
                }
                return array;
            } else if (collection != null) {
                if (isSpecified) {
                    for (String stringValue : requireNonNull(specifiedValue)) {
                        collection.add(ResourceMethodParam.convertValue(parameterHandle(), type(), paramConverter, false, stringValue, source(), key()));
                    }
                } else if (hasExplicitDefault()) {
                    collection.add(defaultValue());
                }
                return readOnly(collection);
            } else {
                return isSpecified ? ResourceMethodParam.convertValue(parameterHandle(), type(), paramConverter, false, requireNonNull(specifiedValue).get(0), source(), key()) : defaultValue();
            }
        }

        private boolean isPathSegmentCollection() {
            if (!(genericType() instanceof ParameterizedType)) return false;
            Type elementType = ((ParameterizedType) genericType()).getActualTypeArguments()[0];
            if (elementType instanceof WildcardType) {
                Type[] upperBounds = ((WildcardType) elementType).getUpperBounds();
                elementType = upperBounds.length == 0 ? elementType : upperBounds[0];
            }
            return PathSegment.class.equals(elementType);
        }

        private static Collection<?> readOnly(Collection<?> collection) {
            return (collection instanceof List) ? Collections.unmodifiableList((List) collection)
                : (collection instanceof SortedSet) ? Collections.unmodifiableSortedSet((SortedSet) collection)
                : (collection instanceof Set) ? Collections.unmodifiableSet((Set) collection)
                : Collections.unmodifiableCollection(collection);
        }

        private @Nullable List<String> getParamValues(MultivaluedMap<String, String> queryParameters, String key, CollectionParameterStrategy cps, boolean isCollectionType) {
            @Nullable List<String> values = queryParameters.get(key);
            if (isCollectionType && values != null && cps == CollectionParameterStrategy.SPLIT_ON_COMMA) {
                List<String> copy = new ArrayList<>(values.size());
                for (String value : values) {
                    value = value.trim();
                    if (value.contains(",")) {
                        String[] bits = value.split("\\s*,\\s*");
                        for (String bit : bits) {
                            if (!bit.isEmpty()) {
                                copy.add(bit);
                            }
                        }
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
                return last.getMatrixParameters().get(key).stream()
                    .map(Jaxutils::leniantUrlDecode)
                    .collect(Collectors.toList());
            }
            return emptyList();
        }

        static @Nullable Collection<Object> createCollection(Class<?> collectionType) {
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
        MessageBodyParam(Introspection introspection, Annotation[] annotations) {
            super(introspection, annotations);
        }
    }

    static class ContextParam extends ResourceMethodParam {
        ContextParam(Introspection introspection, Annotation[] annotations) {
            super(introspection, annotations);
        }
    }

    static class SuspendedParam extends ResourceMethodParam {
        SuspendedParam(Introspection introspection, Annotation[] annotations) {
            super(introspection, annotations);
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

    private static ParamConverter<?> getParamConverter(Introspection parameter,
                                                       Annotation[] annotations,
                                                       List<ParamConverterProvider> paramConverterProviders) {
        Class<?> paramType = parameter.type;
        Type parameterizedType = parameter.genericType;
        if (isSupportedArray(parameter)) {
            paramType = convertedValueType(parameter);
            parameterizedType = arrayComponentType(parameter.genericType);
        } else if (Collection.class.isAssignableFrom(paramType) && parameterizedType instanceof ParameterizedType) {
            Type possiblyWildcardType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            Type type = (possiblyWildcardType instanceof WildcardType) ? ((WildcardType) possiblyWildcardType).getUpperBounds()[0] : possiblyWildcardType;
            if (type instanceof Class) {
                paramType = (Class<?>) type;
            }
        }
        for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
            ParamConverter<?> converter =
                paramConverterProvider.getConverter(paramType, parameterizedType, annotations);
            if (converter == null && RequestBasedParam.createCollection(paramType) != null && parameterizedType instanceof ParameterizedType) {
                // Things like List<A> can be converted with just an 'A' param converter, so let's see if we have that
                ParameterizedType pt = (ParameterizedType) parameterizedType;
                Type[] ata = pt.getActualTypeArguments();
                if (ata.length == 1 && ata[0] instanceof ParameterizedType) {
                    ParameterizedType type = (ParameterizedType) ata[0];
                    Type rawType = type.getRawType();
                    if (rawType instanceof Class) {
                        converter =
                            paramConverterProvider.getConverter((Class) rawType, type, annotations);
                    }
                }
            }
            if (converter != null) return converter;
        }
        throw new MuException("Could not find a suitable ParamConverter for " + parameterizedType + " at " + parameter.parameterHandle.getDeclaringExecutable());
    }

    private static @Nullable Object getDefaultValue(Introspection parameter,
                                                     Class<?> convertedValueType,
                                                     ParamConverter<?> converter,
                                                     boolean lazyDefaultValue) {
        if (!parameter.explicitDefault) {
            return converter instanceof HasDefaultValue ? ((HasDefaultValue) converter).getDefault() : null;
        }
        return convertValue(parameter.parameterHandle, convertedValueType, converter, lazyDefaultValue,
            parameter.explicitDefaultValue, parameter.source, parameter.key);
    }

    private static boolean isSupportedArray(Introspection parameter) {
        return parameter.source != ValueSource.PATH_PARAM
            && parameter.type.isArray()
            && !parameter.type.getComponentType().isPrimitive();
    }

    private static Class<?> convertedValueType(Introspection parameter) {
        if (!isSupportedArray(parameter)) {
            return parameter.type;
        }
        Class<?> componentClass = GenericTypeResolver.rawClass(arrayComponentType(parameter.genericType));
        return componentClass == null ? parameter.type.getComponentType() : componentClass;
    }

    private static Type arrayComponentType(Type arrayType) {
        if (arrayType instanceof Class && ((Class<?>) arrayType).isArray()) {
            return ((Class<?>) arrayType).getComponentType();
        }
        if (arrayType instanceof GenericArrayType) {
            return ((GenericArrayType) arrayType).getGenericComponentType();
        }
        throw new IllegalArgumentException("Not an array type: " + arrayType);
    }

    private static @Nullable Object convertValue(Parameter parameterHandle, Class<?> parameterType, @Nullable ParamConverter<?> converter, boolean skipConverter, @Nullable Object value, ValueSource source, String parameterName) {
        if (source == ValueSource.COOKIE_PARAM && (value == null || value instanceof String)) {
            if (parameterType.equals(Cookie.class)) {
                return value == null ? null : new Cookie(parameterName, (String) value);
            }
        }
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
            } catch (WebApplicationException e) {
                throw e;
            } catch (Exception e) {
                if (source == ValueSource.MATRIX_PARAM || source == ValueSource.QUERY_PARAM || source == ValueSource.PATH_PARAM) {
                    List<String> allowedValues = converter instanceof HasAllowedValues
                        ? ((HasAllowedValues) converter).allowedValues()
                        : emptyList();
                    throw new UriParameterConversionException(parameterName, (String) value, parameterType, allowedValues, e);
                }
                String message = "Could not convert String value \"" + value + "\" to a " + parameterType + " using " + converter + " on parameter " + parameterHandle;
                throw new BadRequestException(message, e);
            }
        }
    }

    enum ValueSource {
        MESSAGE_BODY(null), QUERY_PARAM("query"), MATRIX_PARAM(null), PATH_PARAM("path"), COOKIE_PARAM("cookie"), HEADER_PARAM("header"), FORM_PARAM(null), CONTEXT(null), SUSPENDED(null);

        final @Nullable String openAPIIn;

        ValueSource(@Nullable String openAPIIn) {
            this.openAPIIn = openAPIIn;
        }
    }

    interface HasDefaultValue {
        @Nullable Object getDefault();
    }

    interface HasAllowedValues {
        List<String> allowedValues();
    }

}
