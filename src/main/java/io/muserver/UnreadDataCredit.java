package io.muserver;

/** Whether discarding a cancelled stream's unread body returns connection flow-control credit. */
enum UnreadDataCredit {
    /** Return credit for discarded bytes to the connection so other streams can use it; do not reopen the cancelled stream. */
    REFUND_CONNECTION,
    /** Do not refund here: the connection is closing, or another owner has taken over the stream's accounting. */
    CONNECTION_CLOSED_OR_TRANSFERRED
}
