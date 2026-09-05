package io.muserver;

/** Whether unread bytes remain reusable after stream cancellation. */
enum UnreadDataCredit {
    REFUND_CONNECTION,
    CONNECTION_CLOSED_OR_TRANSFERRED
}
