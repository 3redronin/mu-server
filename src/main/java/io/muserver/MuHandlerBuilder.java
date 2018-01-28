package io.muserver;

public interface MuHandlerBuilder<T extends MuHandler> {
    T build();
}
