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
    final Class<?> type;
    final Type genericType;
    final Annotation[] annotations;
    final ValueSource source;
    final DescriptionData descriptionData;
    final boolean isRequired;

    ResourceMethodParam(int index, ValueSource source, ResolvedParameter parameter, DescriptionData descriptionData, boolean isRequired) {
        this.index = index;
        this.source = source;
        this.parameterHandle = parameter.handle;
        this.type = parameter.type;
        this.genericType = parameter.genericType;
        this.annotations = parameter.annotations;
        this.descriptionData = descriptionData;
        this.isRequired = isRequired;
    }

    static ResourceMethodParam fromParameter(int index, Parameter parameterHandle, List<ParamConverterProvider> paramConverterProviders, UriPattern methodPattern) {
        return fromParameter(index, parameterHandle, parameterHandle,
            parameterHandle.getDeclaringExecutable().getDeclaringClass(), paramConverterProviders, methodPattern);
    }

    static ResourceMethodParam fromParameter(int index, Parameter parameterHandle, Parameter annotationSource, Class<?> concreteClass,
                                             List<ParamConverterProvider> paramConverterProviders, UriPattern methodPattern) {

        ResolvedParameter parameter = new ResolvedParameter(parameterHandle, annotationSource, concreteClass);
        Pattern pattern = null;
        ValueSource source = getSource(annotationSource);
        boolean isRequired = source == ValueSource.PATH_PARAM || hasDeclared(annotationSource, Required.class);
        if (source == ValueSource.MESSAGE_BODY) {
            DescriptionData descriptionData = DescriptionData.fromAnnotation(annotationSource, null);
            return new MessageBodyParam(index, source, parameter, descriptionData, isRequired);
        } else if (source == ValueSource.CONTEXT) {
            return new ContextParam(index, source, parameter);
        } else if (source == ValueSource.SUSPENDED) {
            return new SuspendedParam(index, source, parameter);
        } else {
            boolean encodedRequested = hasDeclared(annotationSource, Encoded.class);
            boolean isDeprecated = hasDeclared(annotationSource, Deprecated.class);
            String key = parameterName(source, annotationSource);
            ParamConverter<?> converter = getParamConverter(parameter, paramConverterProviders);
            boolean lazyDefaultValue = converter.getClass().getDeclaredAnnotation(ParamConverter.Lazy.class) != null;
            boolean explicitDefault = hasDeclared(annotationSource, DefaultValue.class);
            Object defaultValue = getDefaultValue(parameter, annotationSource, converter, lazyDefaultValue, source, key);

            isRequired |= (!explicitDefault && parameter.type.isPrimitive());

            if (key.length() == 0) {
                throw new WebApplicationException("No parameter specified for the " + source + " in " + parameterHandle);
            }
            if (source == ValueSource.PATH_PARAM && methodPattern != null) {
                String regex = methodPattern.regexFor(key);
                if (regex != null) {
                    pattern = Pattern.compile(regex);
                }
            }

            DescriptionData descriptionData = DescriptionData.fromAnnotation(annotationSource, key);
            return new RequestBasedParam(index, source, parameter, defaultValue, encodedRequested, lazyDefaultValue, converter, descriptionData, key, isDeprecated, isRequired, pattern, explicitDefault);
        }
    }

    private static final class ResolvedParameter {
        private final Parameter handle;
        private final Class<?> type;
        private final Type genericType;
        private final Annotation[] annotations;

        private ResolvedParameter(Parameter handle, Parameter annotationSource, Class<?> concreteClass) {
            this.handle = handle;
            this.genericType = GenericTypeResolver.resolve(handle.getParameterizedType(), concreteClass,
                handle.getDeclaringExecutable().getDeclaringClass());
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
                .withDeprecated(isDeprecated ? true : null)
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
                schemaObjectFrom(type, genericType, isRequired)
                    .withDefaultValue(source == ValueSource.PATH_PARAM || !hasExplicitDefault() ? null : defaultValue())
                    .withExternalDocs(externalDoc)
                    .withPattern(patternIfNotDefault)
                    .build()
            );
        }

        RequestBasedParam(int index, ValueSource source, ResolvedParameter parameter, Object defaultValue, boolean encodedRequested, boolean lazyDefaultValue, ParamConverter paramConverter, DescriptionData descriptionData, String key, boolean isDeprecated, boolean isRequired, Pattern pattern, boolean explicitDefault) {
            super(index, source, parameter, descriptionData, isRequired);
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
            return convertValue(parameterHandle, type, paramConverter, skipConverter, defaultValue, source, key);
        }

        public Object getValue(JaxRSRequest jaxRequest, RequestMatcher.MatchedMethod matchedMethod, CollectionParameterStrategy cps) throws IOException {
            MuRequest muRequest = jaxRequest.muRequest;
            Class<?> paramClass = type;
            if (UploadedFile.class.isAssignableFrom(paramClass)) {
                return muRequest.uploadedFile(key);
            } else if (File.class.isAssignableFrom(paramClass)) {
                UploadedFile uf = muRequest.uploadedFile(key);
                return uf == null ? null : uf.asFile();
            } else if (Collection.class.isAssignableFrom(paramClass)) {
                Type t = genericType;
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
            } else if (paramClass.equals(Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key);
                return cookieValues.isEmpty() ? null : new Cookie(key, cookieValues.get(0));
            } else if (paramClass.equals(io.muserver.Cookie.class)) {
                List<String> cookieValues = cookieValue(muRequest, key);
                return cookieValues.isEmpty() ? null : new CookieBuilder().withName(key).withValue(cookieValues.get(0)).build();
            }
            Collection<Object> collection = createCollection(paramClass);
            if (collection != null && source == ValueSource.PATH_PARAM && isPathSegmentCollection()) {
                for (PathSegment segment : matchedMethod.getPathSegments(key)) {
                    collection.add(encodedRequested ? ((MuPathSegment) segment).toEncoded() : segment);
                }
                return readOnly(collection);
            }
            String pathParam = source == ValueSource.PATH_PARAM ? matchedMethod.getPathParam(key) : null;
            List<String> specifiedValue =
                source == ValueSource.PATH_PARAM ? (collection == null
                    ? (pathParam == null ? emptyList() : Collections.singletonList(pathParam))
                    : matchedMethod.getPathParams(key))
                    : source == ValueSource.QUERY_PARAM ? getParamValues(jaxRequest.getUriInfo().getQueryParameters(), key, cps, collection != null)
                    : source == ValueSource.HEADER_PARAM ? getParamValues(jaxRequest.getHeaders(), key, cps, collection != null)
                    : source == ValueSource.FORM_PARAM ? muRequest.form().getAll(key)
                    : source == ValueSource.COOKIE_PARAM ? cookieValue(muRequest, key)
                    : source == ValueSource.MATRIX_PARAM ? matrixParamValue(key, jaxRequest.relativePath())
                    : emptyList();
            boolean isSpecified = specifiedValue != null && !specifiedValue.isEmpty();
            if (encodedRequested && isSpecified) {
                specifiedValue = specifiedValue.stream()
                    .map(value -> source == ValueSource.FORM_PARAM ? FormUrlEncoder.formUrlEncode(value) : Mutils.urlEncode(value))
                    .collect(Collectors.toList());
            }
            if (collection != null) {
                if (isSpecified) {
                    for (String stringValue : specifiedValue) {
                        collection.add(ResourceMethodParam.convertValue(parameterHandle, type, paramConverter, false, stringValue, source, key));
                    }
                } else if (hasExplicitDefault()) {
                    collection.add(defaultValue());
                }
                return readOnly(collection);
            } else {
                return isSpecified ? ResourceMethodParam.convertValue(parameterHandle, type, paramConverter, false, specifiedValue.get(0), source, key) : defaultValue();
            }
        }

        private boolean isPathSegmentCollection() {
            if (!(genericType instanceof ParameterizedType)) return false;
            Type elementType = ((ParameterizedType) genericType).getActualTypeArguments()[0];
            if (elementType instanceof WildcardType) {
                Type[] upperBounds = ((WildcardType) elementType).getUpperBounds();
                elementType = upperBounds.length == 0 ? elementType : upperBounds[0];
            }
            return elementType instanceof Class && PathSegment.class.isAssignableFrom((Class<?>) elementType);
        }

        private static Collection<?> readOnly(Collection<?> collection) {
            return (collection instanceof List) ? Collections.unmodifiableList((List) collection)
                : (collection instanceof SortedSet) ? Collections.unmodifiableSortedSet((SortedSet) collection)
                : (collection instanceof Set) ? Collections.unmodifiableSet((Set) collection)
                : Collections.unmodifiableCollection(collection);
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
                return last.getMatrixParameters().get(key).stream()
                    .map(Jaxutils::leniantUrlDecode)
                    .collect(Collectors.toList());
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
        MessageBodyParam(int index, ValueSource source, ResolvedParameter parameter, DescriptionData descriptionData, boolean isRequired) {
            super(index, source, parameter, descriptionData, isRequired);
        }
    }

    static class ContextParam extends ResourceMethodParam {
        ContextParam(int index, ValueSource source, ResolvedParameter parameter) {
            super(index, source, parameter, null, true);
        }
    }

    static class SuspendedParam extends ResourceMethodParam {
        SuspendedParam(int index, ValueSource source, ResolvedParameter parameter) {
            super(index, source, parameter, null, true);
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

    private static ParamConverter<?> getParamConverter(ResolvedParameter parameter, List<ParamConverterProvider> paramConverterProviders) {
        Class<?> paramType = parameter.type;
        Type parameterizedType = parameter.genericType;
        if (Collection.class.isAssignableFrom(paramType) && parameterizedType instanceof ParameterizedType) {
            Type possiblyWildcardType = ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            Type type = (possiblyWildcardType instanceof WildcardType) ? ((WildcardType) possiblyWildcardType).getUpperBounds()[0] : possiblyWildcardType;
            if (type instanceof Class) {
                paramType = (Class<?>) type;
            }
        }
        for (ParamConverterProvider paramConverterProvider : paramConverterProviders) {
            ParamConverter<?> converter = paramConverterProvider.getConverter(paramType, parameterizedType, parameter.annotations);
            if (converter == null && RequestBasedParam.createCollection(paramType) != null && parameterizedType instanceof ParameterizedType) {
                // Things like List<A> can be converted with just an 'A' param converter, so let's see if we have that
                ParameterizedType pt = (ParameterizedType) parameterizedType;
                Type[] ata = pt.getActualTypeArguments();
                if (ata.length == 1 && ata[0] instanceof ParameterizedType) {
                    ParameterizedType type = (ParameterizedType) ata[0];
                    Type rawType = type.getRawType();
                    if (rawType instanceof Class) {
                        converter = paramConverterProvider.getConverter((Class) rawType, type, parameter.annotations);
                    }
                }
            }
            if (converter != null) return converter;
        }
        throw new MuException("Could not find a suitable ParamConverter for " + parameterizedType + " at " + parameter.handle.getDeclaringExecutable());
    }

    private static Object getDefaultValue(ResolvedParameter parameter, Parameter annotationSource, ParamConverter<?> converter, boolean lazyDefaultValue, ValueSource source, String parameterName) {
        DefaultValue annotation = annotationSource.getDeclaredAnnotation(DefaultValue.class);
        if (annotation == null) {
            return converter instanceof HasDefaultValue ? ((HasDefaultValue) converter).getDefault() : null;
        }
        return convertValue(parameter.handle, parameter.type, converter, lazyDefaultValue, annotation.value(), source, parameterName);
    }

    private static Object convertValue(Parameter parameterHandle, Class<?> parameterType, ParamConverter<?> converter, boolean skipConverter, Object value, ValueSource source, String parameterName) {
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
                    throw new UriParameterConversionException(parameterName, (String) value, parameterType, e);
                }
                String message = "Could not convert String value \"" + value + "\" to a " + parameterType + " using " + converter + " on parameter " + parameterHandle;
                throw new BadRequestException(message, e);
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
