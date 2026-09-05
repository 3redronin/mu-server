package io.muserver.internal;

/** Fatal VM/thread failures are never converted to ordinary application failures.
 * @hidden
 */
public final class FatalErrors {
    private FatalErrors() { }
    public static void rethrow(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }
}
