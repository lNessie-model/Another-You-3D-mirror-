package com.mirror.bench;

import android.media.Image;
import android.os.SystemClock;
import org.json.JSONObject;

interface FrameInput extends AutoCloseable {
    final class Frame implements AutoCloseable {
        final Image image;
        final byte[] nv21;
        final long receivedNs;
        Frame(Image image) { this(image,null,SystemClock.elapsedRealtimeNanos()); }
        Frame(byte[] nv21,long receivedNs) { this(null,nv21,receivedNs); }
        private Frame(Image image,byte[] nv21,long receivedNs) {
            this.image=image; this.nv21=nv21; this.receivedNs=receivedNs;
        }
        @Override public void close() { if(image!=null) image.close(); }
    }
    Frame take() throws Exception;
    void beginMeasurement();
    JSONObject summary(double seconds) throws Exception;
    @Override void close();
}
