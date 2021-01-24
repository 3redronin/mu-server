package io.muserver;

import java.util.concurrent.Future;

/**
 * @deprecated This interface is no longer used. Instead call {@link MuRequest#handleAsync()} from a standard Mu Handler.
 */
@Deprecated
public class AsyncContext implements ResponseInfo {

    /**
     * @deprecated Use {@link MuRequest#attribute(String)} instead.
     */
    @Deprecated
    public Object state;

    @Deprecated
    public Future<Void> complete(boolean forceDisconnect) {
        throw new MuException("This class has been deprecated");
    }

    @Deprecated
    boolean isComplete() {
        throw new MuException("This class has been deprecated");
    }

    @Override
    @Deprecated
    public long duration() {
        throw new MuException("This class has been deprecated");
    }

    @Override
    @Deprecated
    public boolean completedSuccessfully() {
        throw new MuException("This class has been deprecated");
    }

    @Override
    @Deprecated
    public MuRequest request() {
        throw new MuException("This class has been deprecated");
    }

    @Override
    @Deprecated
    public MuResponse response() {
        throw new MuException("This class has been deprecated");
    }

}
