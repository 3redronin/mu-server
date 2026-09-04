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
import java.util.List;

/**
 * The providers registered for a single {@link RestHandler}.
 *
 * <p>This is initialized in two phases so that reader and writer factories can retain the final
 * {@link Providers} instance while the provider list is being assembled. Lookups are only valid after initialization.</p>
 */
final class JaxRSProviders implements Providers {

    private volatile @Nullable State state;

    synchronized void initialize(EntityProviders entityProviders,
                                 List<ExceptionMapperRegistration<?>> exceptionMappers,
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
            Collections.emptyList(), Collections.emptyList());
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
        ExceptionMapperRegistration<?> best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (ExceptionMapperRegistration<?> registration : requiredState().exceptionMappers) {
            Class<? extends Throwable> mappedType = registration.exceptionType;
            if (!mappedType.isAssignableFrom(type)) {
                continue;
            }
            int depth = 0;
            Class<?> candidate = type;
            while (candidate != null) {
                if (candidate.equals(mappedType)) {
                    if (depth < bestDepth || (depth == bestDepth && registration.preferredTo(best))) {
                        best = registration;
                        bestDepth = depth;
                    }
                    break;
                }
                candidate = candidate.getSuperclass();
                depth++;
            }
        }
        return best == null ? null : (ExceptionMapper<T>) best.mapper;
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

    static final class ExceptionMapperRegistration<T extends Throwable> {
        final Class<T> exceptionType;
        final ExceptionMapper<T> mapper;
        final boolean isBuiltIn;
        final int priority;

        ExceptionMapperRegistration(Class<T> exceptionType, ExceptionMapper<T> mapper, boolean isBuiltIn) {
            this.exceptionType = exceptionType;
            this.mapper = mapper;
            this.isBuiltIn = isBuiltIn;
            this.priority = PrioritizedComponent.priorityOf(mapper);
        }

        boolean preferredTo(@Nullable ExceptionMapperRegistration<?> other) {
            if (other == null) {
                return true;
            }
            if (isBuiltIn != other.isBuiltIn) {
                return !isBuiltIn;
            }
            return priority < other.priority;
        }
    }

    private static int mediaTypeSpecificity(MediaType mediaType) {
        return mediaType.isWildcardType()
            ? 2
            : MediaTypeHeaderDelegate.isWildcardSubtype(mediaType.getSubtype()) ? 1 : 0;
    }

    private static final class State {
        private final EntityProviders entityProviders;
        private final List<ExceptionMapperRegistration<?>> exceptionMappers;
        private final List<ContextResolverRegistration<?>> contextResolvers;

        private State(EntityProviders entityProviders,
                      List<ExceptionMapperRegistration<?>> exceptionMappers,
                      List<ContextResolverRegistration<?>> contextResolvers) {
            this.entityProviders = entityProviders;
            this.exceptionMappers = Collections.unmodifiableList(new ArrayList<>(exceptionMappers));
            this.contextResolvers = Collections.unmodifiableList(new ArrayList<>(contextResolvers));
        }
    }
}
