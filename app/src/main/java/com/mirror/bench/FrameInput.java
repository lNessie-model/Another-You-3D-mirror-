package com.mirror.bench;

import android.media.Image;
import android.os.SystemClock;
import org.json.JSONObject;

interface FrameInput extends AutoCloseable {
    final class Frame implements AutoCloseable {
        final Image image;
        final byte[] nv21;
        final long receivedNs;
        private final Runnable afterClose;
        private boolean closed;
        Frame(Image image) { this(image,null); }
        Frame(Image image,Runnable afterClose) { this(image,null,SystemClock.elapsedRealtimeNanos(),afterClose); }
        Frame(byte[] nv21,long receivedNs) { this(null,nv21,receivedNs,null); }
        private Frame(Image image,byte[] nv21,long receivedNs,Runnable afterClose) {
            this.image=image; this.nv21=nv21; this.receivedNs=receivedNs; this.afterClose=afterClose;
        }
        @Override public void close() {
            synchronized(this) { if(closed) return; closed=true; }
            try { if(image!=null) image.close(); }
            finally { if(afterClose!=null) afterClose.run(); }
        }
    }
    Frame take() throws Exception;
    void beginMeasurement();
    JSONObject summary(double seconds) throws Exception;
    @Override void close();
}
