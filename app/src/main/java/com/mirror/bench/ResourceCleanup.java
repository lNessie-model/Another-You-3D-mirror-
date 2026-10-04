package com.mirror.bench;

import java.util.ArrayList;
import java.util.List;

/** Attempts every close while retaining the operation's first failure and named diagnostics. */
final class ResourceCleanup {
    record Failure(String resource,Throwable error) {}

    /** Packet.release() has no checked exception; suitable for try-with-resources ownership. */
    @FunctionalInterface
    interface Release extends AutoCloseable {
        @Override void close();
    }

    private Throwable failure;
    private final ArrayList<Failure> errors=new ArrayList<>();

    ResourceCleanup(Throwable originalFailure) { failure=originalFailure; }

    void close(String name,AutoCloseable resource) {
        if(resource==null) return;
        try { resource.close(); }
        catch(Throwable error) {
            errors.add(new Failure(name,error));
            if(failure==null) failure=error;
            else if(failure!=error) failure.addSuppressed(error);
        }
    }

    Throwable failure() { return failure; }
    List<Failure> errors() { return java.util.Collections.unmodifiableList(errors); }
}
