package com.mirror.bench;

/** Call the OVR entry point absent from the Android Java GLES bindings. */
final class MultiviewGl {
    static { System.loadLibrary("mirror_gl"); }
    static native void attach(int attachment,int texture,int baseView,int count);
    private MultiviewGl() {}
}
