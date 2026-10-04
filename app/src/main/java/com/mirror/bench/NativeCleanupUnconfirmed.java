package com.mirror.bench;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** A native owner could not confirm cleanup; the process must not silently retry it. */
public final class NativeCleanupUnconfirmed extends IllegalStateException {
    public NativeCleanupUnconfirmed(String message) { super(message); }
    NativeCleanupUnconfirmed(String message,Throwable cause) { super(message,cause); }

    /** Bounded diagnostic traversal. Oversized exception graphs fail closed. */
    static boolean contains(Throwable error) {
        if(error==null)return false;
        IdentityHashMap<Throwable,Boolean> seen=new IdentityHashMap<>();
        ArrayDeque<Throwable> pending=new ArrayDeque<>();
        pending.add(error);seen.put(error,Boolean.TRUE);
        while(!pending.isEmpty()) {
            Throwable next=pending.removeFirst();
            if(next instanceof NativeCleanupUnconfirmed)return true;
            Throwable cause=next.getCause();
            if(cause!=null&&!seen.containsKey(cause)) {
                if(seen.size()==64)return true;
                seen.put(cause,Boolean.TRUE);pending.addLast(cause);
            }
            for(Throwable suppressed:next.getSuppressed())if(!seen.containsKey(suppressed)) {
                if(seen.size()==64)return true;
                seen.put(suppressed,Boolean.TRUE);pending.addLast(suppressed);
            }
        }
        return false;
    }
}
