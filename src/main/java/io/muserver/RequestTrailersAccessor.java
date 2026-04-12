package io.muserver;

import org.jspecify.annotations.Nullable;

interface RequestTrailersAccessor {
    boolean isRequestBodyComplete();
    @Nullable Headers trailers();
}

