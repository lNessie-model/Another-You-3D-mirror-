package com.mirror.bench;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** Read-only mapped NV21 clip, paced at its original average camera rate, latest frame only. */
final class ReplayFrameSource implements FrameInput {
    private final RandomAccessFile file;
    private final MappedByteBuffer mapped;
    private final byte[] pixels;
    private final int frameCount,frameBytes;
    private final double fps;
    private final String name;
    private long startNs,lastOrdinal=-1,consumed,skipped;
    private boolean closed;
    ReplayFrameSource(Context context,int width,int height,String name,double fps,boolean preload) throws Exception {
        if(name==null||!name.matches("[a-zA-Z0-9_-]+")||!Double.isFinite(fps)||fps<=0||fps>60||
                width<=0||height<=0||(width%2)!=0||(height%2)!=0||(long)width*height*3/2>512L*1024*1024)
            throw new IllegalArgumentException("Invalid replay clip or rate");
        this.name=name; this.fps=fps; frameBytes=width*height*3/2;
        File path=new File(new File(context.getFilesDir(),"recordings"),name+".nv21");
        long size=path.length();
        if(size<frameBytes||size%frameBytes!=0||size>512L*1024*1024)
            throw new IllegalArgumentException("Replay must contain whole NV21 frames and fit the 512 MiB budget");
        file=new RandomAccessFile(path,"r");
        mapped=file.getChannel().map(FileChannel.MapMode.READ_ONLY,0,size);
        if(preload) mapped.load();
        frameCount=(int)(size/frameBytes); pixels=new byte[frameBytes];
        beginMeasurement();
    }
    @Override public Frame take() throws Exception {
        while(!closed) {
            long ordinal=(long)((SystemClock.elapsedRealtimeNanos()-startNs)*fps/1e9);
            if(ordinal>lastOrdinal) {
                if(lastOrdinal>=0) skipped+=ordinal-lastOrdinal-1;
                lastOrdinal=ordinal; consumed++;
                mapped.position((int)(ordinal%frameCount)*frameBytes); mapped.get(pixels);
                return new Frame(pixels,startNs+(long)(ordinal*1e9/fps));
            }
            SystemClock.sleep(1);
        }
        throw new IllegalStateException("Replay closed");
    }
    @Override public void beginMeasurement() {
        startNs=SystemClock.elapsedRealtimeNanos(); lastOrdinal=-1; consumed=0; skipped=0;
    }
    @Override public JSONObject summary(double seconds) throws Exception {
        return new JSONObject().put("source","memory-mapped NV21 recording; no USB capture or video decoder")
                .put("record_id",name).put("clip_frames",frameCount).put("nominal_fps",fps)
                .put("consumed_frames",consumed).put("skipped_frames",skipped)
                .put("mapped_mib",mapped.capacity()/1048576.0).put("error","");
    }
    @Override public void close() {
        closed=true;
        try { file.close(); } catch(Exception ignored) {}
        // Mapping is read-only; process exit releases its pages and virtual address range.
    }
}
