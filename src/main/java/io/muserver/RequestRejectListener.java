package io.muserver;

/**
 * A callback for listening to requests that are rejected at the protocol level, before they
 * become a normal request/response exchange. This happens, for example, when a request is
 * rejected with a <code>431</code> because its headers are too large, or a <code>503</code>
 * because the server is overloaded.
 * <p>Because no {@link MuRequest} or {@link MuResponse} is created for these requests, they are
 * never reported to a {@link ResponseCompleteListener}.</p>
 * @see MuServerBuilder#addRequestRejectListener(RequestRejectListener)
 */
public interface RequestRejectListener {

    /**
     * Called when a request is rejected before it becomes a normal exchange.
     * @param info Information about the rejected request.
     */
    void onRejected(RejectedRequest info);
}
