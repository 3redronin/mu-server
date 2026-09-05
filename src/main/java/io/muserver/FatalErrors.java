package io.muserver;

/** Rethrows VM and thread termination errors after the caller has performed cleanup. */
final class FatalErrors {
    private FatalErrors() { }
    static void rethrow(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }
}
