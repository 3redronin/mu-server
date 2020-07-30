package io.muserver;

public interface ConnectionState {
    interface Listener {
        default void onWriteable() throws Exception{};
        default void onUnWriteable() throws Exception{};
        default void onConnectionClose() throws Exception{};
    }
    void registerConnectionStateListener(Listener listener);
}
