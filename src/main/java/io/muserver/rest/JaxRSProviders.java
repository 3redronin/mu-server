package io.muserver.rest;

import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The providers registered for a single {@link RestHandler}.
 *
 * <p>This is initialized in two phases so that reader and writer factories can retain the final
 * {@link Providers} instance while the provider list is being assembled. Lookups are only valid after initialization.</p>
 */
final class JaxRSProviders implements Providers {

    private volatile @Nullable State state;

    synchronized void initialize(EntityProviders entityProviders,
                                 Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers,
                                 List<ContextResolverRegistration<?>> contextResolvers) {
        if (state != null) {
            throw new IllegalStateException("Providers have already been initialized");
        }
        state = new State(entityProviders, exceptionMappers, contextResolvers);
    }

    static JaxRSProviders builtInReadersOnly() {
        JaxRSProviders providers = new JaxRSProviders();
        providers.initialize(new EntityProviders(
                EntityProviders.builtInReaders(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()),
            Collections.emptyMap(), Collections.emptyList());
        return providers;
    }

    private State requiredState() {
        State current = state;
        if (current == null) {
            throw new IllegalStateException("Providers cannot be queried until RestHandlerBuilder.build() has completed");
        }
        return current;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType,
                                                                   Annotation[] annotations, MediaType mediaType) {
        return (MessageBodyReader<T>) requiredState().entityProviders.findReader(type, genericType, annotations, mediaType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType,
                                                                   Annotation[] annotations, MediaType mediaType) {
        return (MessageBodyWriter<T>) requiredState().entityProviders.findWriter(type, genericType, annotations, mediaType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Throwable> @Nullable ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        ExceptionMapper<?> best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (Map.Entry<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> entry
            : requiredState().exceptionMappers.entrySet()) {
            Class<? extends Throwable> mappedType = entry.getKey();
            if (!mappedType.isAssignableFrom(type)) {
                continue;
            }
            int depth = 0;
            Class<?> candidate = type;
            while (candidate != null) {
                if (candidate.equals(mappedType)) {
                    if (depth < bestDepth) {
                        best = entry.getValue();
                        bestDepth = depth;
                    }
                    break;
                }
                candidate = candidate.getSuperclass();
                depth++;
            }
        }
        return (ExceptionMapper<T>) best;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @Nullable ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        List<ContextResolverRegistration<?>> matches = new ArrayList<>();
        for (ContextResolverRegistration<?> registration : requiredState().contextResolvers) {
            if (contextType.equals(registration.contextType) && registration.supports(mediaType)) {
                matches.add(registration);
            }
        }
        matches.sort(Comparator.comparingInt(registration -> registration.mediaTypeSpecificity(mediaType)));
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return (ContextResolver<T>) matches.get(0).resolver;
        }
        return type -> {
            for (ContextResolverRegistration<?> registration : matches) {
                Object context = registration.resolver.getContext(type);
                if (context != null) {
                    return (T) context;
                }
            }
            return null;
        };
    }

    List<ProviderWrapper<MessageBodyWriter<?>>> writers() {
        return requiredState().entityProviders.writers;
    }

    boolean isBuiltInWriter(MessageBodyWriter<?> writer) {
        return requiredState().entityProviders.isBuiltInWriter(writer);
    }

    static <T> MessageBodyReader<T> requireMessageBodyReader(Providers providers, Class<T> type, Type genericType,
                                                              Annotation[] annotations, MediaType mediaType) {
        MessageBodyReader<T> reader = providers.getMessageBodyReader(type, genericType, annotations, mediaType);
        if (reader == null) {
            throw new NotSupportedException("Could not find a suitable entity provider to read " + type);
        }
        return reader;
    }

    static <T> MessageBodyWriter<T> requireMessageBodyWriter(Providers providers, Class<T> type, Type genericType,
                                                              Annotation[] annotations, MediaType mediaType) {
        MessageBodyWriter<T> writer = providers.getMessageBodyWriter(type, genericType, annotations, mediaType);
        if (writer == null) {
            throw new InternalServerErrorException("Could not find a suitable entity provider to write " + type);
        }
        return writer;
    }

    static final class ContextResolverRegistration<T> {
        final Class<T> contextType;
        final ContextResolver<T> resolver;
        private final List<MediaType> mediaTypes;

        ContextResolverRegistration(Class<T> contextType, ContextResolver<T> resolver) {
            this.contextType = contextType;
            this.resolver = resolver;
            this.mediaTypes = MediaTypeDeterminer.supportedProducesTypes(resolver.getClass());
        }

        private boolean supports(MediaType mediaType) {
            return mediaTypes.isEmpty()
                || mediaTypes.stream().anyMatch(candidate -> MediaTypeHeaderDelegate.isCompatible(candidate, mediaType));
        }

        private int mediaTypeSpecificity(MediaType requestedType) {
            return mediaTypes.stream()
                .filter(candidate -> MediaTypeHeaderDelegate.isCompatible(candidate, requestedType))
                .mapToInt(JaxRSProviders::mediaTypeSpecificity)
                .min()
                .orElse(2);
        }
    }

    private static int mediaTypeSpecificity(MediaType mediaType) {
        return mediaType.isWildcardType()
            ? 2
            : MediaTypeHeaderDelegate.isWildcardSubtype(mediaType.getSubtype()) ? 1 : 0;
    }

    private static final class State {
        private final EntityProviders entityProviders;
        private final Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers;
        private final List<ContextResolverRegistration<?>> contextResolvers;

        private State(EntityProviders entityProviders,
                      Map<Class<? extends Throwable>, ExceptionMapper<? extends Throwable>> exceptionMappers,
                      List<ContextResolverRegistration<?>> contextResolvers) {
            this.entityProviders = entityProviders;
            this.exceptionMappers = Collections.unmodifiableMap(new HashMap<>(exceptionMappers));
            this.contextResolvers = Collections.unmodifiableList(new ArrayList<>(contextResolvers));
        }
    }
}
