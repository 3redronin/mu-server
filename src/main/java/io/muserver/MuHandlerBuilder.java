package io.muserver;

/**
 * A builder for {@link MuHandler} objects
 * @param <T> The type of handler this builder builds
 */
public interface MuHandlerBuilder<T extends MuHandler> {

    /**
     * Builds the configured handler.
     *
     * @return A newly built {@link MuHandler}
     */
    T build();
}
