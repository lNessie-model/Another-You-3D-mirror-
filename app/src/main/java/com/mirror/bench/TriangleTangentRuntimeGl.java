package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.FloatBuffer;
import org.json.JSONObject;

/** One GL owner, one fixed PN snapshot and one table texture. Never retains a worker lease/view. */
final class TriangleTangentRuntimeGl implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Object metrics=new Object();
    private final TriangleTangentTable table;
    private final float[] snapshot;
    private final FloatBuffer ownedPn;
    private final int[] scratch=new int[1],unpackKeys={GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS},unpackValues=new int[4];
    private FloatBuffer packedView;
    private volatile int texture;
    private boolean bound,captured;
    private volatile boolean closed;
    private volatile String error="";
    private volatile long snapshotRevision=-1;
    private long copies,builds,uploads,uploadedBuild,snapshotBytes;
    private double copyMs,buildMs,uploadMs,meanCopyMs,meanBuildMs,meanUploadMs;

    TriangleTangentRuntimeGl(AvatarAsset.Primitive source){
        table=new TriangleTangentTable(source.vertexCount(),source.indices(),source.texCoords());
        snapshot=new float[source.vertexCount()*6];ownedPn=FloatBuffer.wrap(snapshot);
        if(integer(GLES30.GL_MAJOR_VERSION)<3||(integer(GLES30.GL_MAJOR_VERSION)==3&&integer(GLES30.GL_MINOR_VERSION)<2))
            throw new IllegalStateException("Triangle tangent runtime requires actual GLES3.2");
        int active=integer(GLES30.GL_ACTIVE_TEXTURE);boolean ownedBinding=false;Throwable primary=null;
        try{
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
            // Unit3 is reserved for this qualified ordinary runtime scene. Existing runtime passes use 0..2.
            if(integer(GLES30.GL_TEXTURE_BINDING_2D)!=0||integer(GLES30.GL_SAMPLER_BINDING)!=0)
                throw new IllegalStateException("Triangle tangent runtime requires unused texture/sampler unit3");
            GLES30.glGenTextures(1,scratch,0);texture=scratch[0];if(texture==0)throw new IllegalStateException("No tangent runtime texture");
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);
            ownedBinding=true;
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA32F,table.width(),table.height());
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            check("runtime tangent storage");
        }catch(RuntimeException|Error failure){
            primary=failure;
            if(texture!=0){scratch[0]=texture;texture=0;try{GLES30.glDeleteTextures(1,scratch,0);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}}
            throw failure;
        }finally{
            ResourceCleanup cleanup=new ResourceCleanup(primary);
            if(ownedBinding)cleanup.close("initial runtime table binding",()->GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0));
            cleanup.close("initial runtime active unit",()->GLES30.glActiveTexture(active));
            if(primary==null&&cleanup.failure()!=null)throw new IllegalStateException("Runtime table initialization restore",cleanup.failure());
        }
    }
    /** Caller has just checked success of glBufferSubData for exactly these PN bytes. */
    void captureUploaded(FloatBuffer source,long revision){
        requireOwner();long start=System.nanoTime();
        try{
            if(source==null||source.remaining()!=snapshot.length||revision<0)throw new IllegalArgumentException("Complete successfully uploaded PN and revision required");
            int position=source.position();try{source.get(snapshot);}finally{source.position(position);}
            // Bulk native-array transfer, no duplicate FloatBuffer and no reference to source escapes.
            ownedPn.position(0);captured=true;snapshotRevision=revision;
            synchronized(metrics){copies++;snapshotBytes+=(long)snapshot.length*4;copyMs=(System.nanoTime()-start)/1e6;meanCopyMs+=(copyMs-meanCopyMs)/copies;}
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}
    }
    void prepare(float[] actualWorld,long uploadedRevision){
        requireOwner();
        try{
            if(!captured||uploadedRevision!=snapshotRevision)throw new IllegalStateException("Tangent PN snapshot differs from successfully uploaded primary revision");
            long start=System.nanoTime();boolean changed=table.update(ownedPn,actualWorld,uploadedRevision);
            if(changed){synchronized(metrics){builds++;buildMs=(System.nanoTime()-start)/1e6;meanBuildMs+=(buildMs-meanBuildMs)/builds;}}
            if(uploadedBuild==table.rebuilds())return;
            if(packedView==null)packedView=table.packed();
            upload();
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}
    }
    private void upload(){
        long start=System.nanoTime();int pbo=integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING),active=integer(GLES30.GL_ACTIVE_TEXTURE);
        for(int i=0;i<4;i++)unpackValues[i]=integer(unpackKeys[i]);
        Throwable primary=null;
        try{
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);
            for(int key:unpackKeys)GLES30.glPixelStorei(key,key==GLES30.GL_UNPACK_ALIGNMENT?4:0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);
            packedView.position(0);GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,table.width(),table.height(),GLES30.GL_RGBA,GLES30.GL_FLOAT,packedView);
            check("runtime tangent table upload");uploadedBuild=table.rebuilds();
            synchronized(metrics){uploads++;uploadMs=(System.nanoTime()-start)/1e6;meanUploadMs+=(uploadMs-meanUploadMs)/uploads;}
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{
            Throwable cleanup=null;
            try{GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);}catch(RuntimeException|Error e){cleanup=e;}
            try{GLES30.glActiveTexture(active);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
            try{GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,pbo);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
            for(int i=0;i<4;i++)try{GLES30.glPixelStorei(unpackKeys[i],unpackValues[i]);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
            if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw new IllegalStateException("Runtime tangent upload restore",cleanup);}
        }
    }
    /** Caller is Scene.drawPass after bindAtlas, whose active-unit contract is unit0. No queries. */
    void bind(){requireOwner();if(!captured||uploadedBuild==0||uploadedBuild!=table.rebuilds()||bound)throw new IllegalStateException("Unready/nested runtime table");bound=true;GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}
    void unbind(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Runtime tangent owner differs");if(!bound)return;bound=false;Throwable failure=null;
        try{GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);}catch(RuntimeException|Error e){failure=e;}
        try{GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}catch(RuntimeException|Error e){failure=append(failure,e);}
        if(failure!=null)throw new IllegalStateException("Runtime tangent draw binding restore",failure);
    }
    void fail(Throwable failure){if(error.isEmpty())error=failure.toString();}
    boolean healthy(){return error.isEmpty()&&!closed;}
    int texture(){return texture;}
    long revision(){return snapshotRevision;}
    JSONObject status()throws Exception{
        synchronized(metrics){return new JSONObject().put("requested",true).put("error",error).put("closed",closed)
            .put("primary_snapshot_revision",snapshotRevision).put("snapshot_copies",copies).put("table_builds",builds).put("table_uploads",uploads)
            .put("snapshot_logical_bytes",(long)snapshot.length*4).put("snapshot_copied_logical_bytes",snapshotBytes)
            .put("table_texture_logical_bytes",(long)table.width()*table.height()*16).put("table_uploaded_logical_bytes",uploads*table.width()*table.height()*16L)
            .put("table_cpu_payload_logical_bytes",table.triangles()*12L+(snapshot.length/6L)*20+table.width()*table.height()*32L+64)
            .put("snapshot_copy_last_ms",copyMs).put("snapshot_copy_mean_ms",meanCopyMs).put("table_build_last_ms",buildMs).put("table_build_mean_ms",meanBuildMs).put("table_upload_last_ms",uploadMs).put("table_upload_mean_ms",meanUploadMs)
            .put("timing_scope","CPU wall time on GL owner; upload includes API/driver stalls, not GPU execution; online means since scene creation")
            .put("byte_scope","Logical PN/table payload only; not measured GPU residency")
            .put("table_width",table.width()).put("table_height",table.height()).put("table_triangles",table.triangles()).put("table_texture",texture);}
    }
    private void requireOwner(){if(Thread.currentThread()!=owner||closed||!error.isEmpty())throw new IllegalStateException("Triangle tangent owner closed/failed/differs: "+error);}
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Runtime tangent close owner differs");if(closed)return;
        Throwable failure=null;try{unbind();}catch(RuntimeException|Error e){failure=e;}
        closed=true;int old=texture;texture=0;
        try{if(old!=0){scratch[0]=old;GLES30.glDeleteTextures(1,scratch,0);}check("runtime tangent cleanup");}
        catch(RuntimeException|Error cleanup){if(failure!=null)failure.addSuppressed(cleanup);else failure=cleanup;}
        if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException e)throw e;
    }
    private int integer(int key){GLES30.glGetIntegerv(key,scratch,0);return scratch[0];}
    private static Throwable append(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    private static void check(String label){int error=GLES30.glGetError();if(error!=0)throw new IllegalStateException(label+" GL error "+error);}
}
