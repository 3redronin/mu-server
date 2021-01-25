package io.muserver;

interface ConnectionState {
    interface Listener {
        default void onWriteable() throws Exception{};
        default void onUnWriteable() throws Exception{};
    }
    void registerConnectionStateListener(Listener listener);
}
