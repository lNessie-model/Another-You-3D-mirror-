package com.mirror.bench;

/** One GL-owner/context cache. Publishes only complete captures; driver owns the actual GPU objects. */
final class AvatarBackgroundCache {
    interface Driver {
        void allocate(int width,int height,int views,int group);
        void capture(int base,int group,float[] viewProjections,int offset,float aspect);
        void finishCapture();
        void restore(int base,int group);
        /** Must also release partially allocated objects after allocate/capture fails. */
        void release();
    }
    static final long MAX_STORAGE_BYTES=64L*1024*1024;
    private final Driver driver;
    private final Thread owner=Thread.currentThread();
    private final long context,bytes;
    private final int width,height,views,group;
    private boolean allocated,closed;
    private Key key;
    private long builds;

    AvatarBackgroundCache(Driver driver,long context,int width,int height,int views,int group){
        if(driver==null||context<1||width<1||height<1||width>4096||height>4096||views<1||views>32
                ||(group!=1&&group!=4)||views%group!=0)throw new IllegalArgumentException("Invalid background cache configuration");
        bytes=(long)width*height*views*6;
        if(bytes>MAX_STORAGE_BYTES)throw new IllegalArgumentException("Background cache exceeds 64 MiB storage limit");
        this.driver=driver;this.context=context;this.width=width;this.height=height;this.views=views;this.group=group;
    }
    boolean prepare(long generation,Object scene,String modelSha,float aspect,float[] staticState,float[] vp){
        checkOwner(generation);if(closed)throw new IllegalStateException("Background cache is closed");
        try{
            if(scene==null||!validHash(modelSha)||!(aspect>0)||!Float.isFinite(aspect)
                    ||staticState==null||staticState.length<16||staticState.length%16!=0||vp==null||vp.length!=views*16)
                throw new IllegalArgumentException("Incomplete background cache key");
            if(key!=null&&key.matches(scene,modelSha,aspect,staticState,vp))return false;
            key=null;
            for(float x:staticState)if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite static transform");
            for(float x:vp)if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite camera matrix");
            if(!allocated){driver.allocate(width,height,views,group);allocated=true;}
            for(int base=0;base<views;base+=group)driver.capture(base,group,vp,base*16,aspect);
            driver.finishCapture();
            key=new Key(scene,modelSha,aspect,staticState,vp);
            if(builds<Long.MAX_VALUE)builds++;
            return true;
        }catch(RuntimeException|Error failure){
            key=null;closed=true;allocated=false;
            try{driver.release();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    void restoreGroup(long generation,int base){
        checkOwner(generation);
        if(closed||key==null)throw new IllegalStateException("No complete background capture");
        if(base<0||base>views-group||base%group!=0)throw new IllegalArgumentException("Invalid background view group");
        driver.restore(base,group);
    }
    void close(long generation){
        checkOwner(generation);if(closed)return;
        key=null;closed=true;
        if(allocated){allocated=false;driver.release();}
    }
    long builds(){return builds;}
    long storageBytes(){return bytes;}
    private void checkOwner(long generation){
        if(Thread.currentThread()!=owner||generation!=context)throw new IllegalStateException("Background cache owner/context mismatch");
    }
    private static boolean validHash(String hash){
        if(hash==null||hash.length()!=64)return false;
        for(int i=0;i<64;i++){char c=hash.charAt(i);if((c<'0'||c>'9')&&(c<'a'||c>'f'))return false;}
        return true;
    }
    private static boolean same(float[] a,float[] b){
        if(a.length!=b.length)return false;
        for(int i=0;i<a.length;i++)if(Float.floatToRawIntBits(a[i])!=Float.floatToRawIntBits(b[i]))return false;
        return true;
    }
    private static final class Key {
        final Object scene;final String hash;final int aspectBits;final float[] state,vp;
        Key(Object scene,String hash,float aspect,float[] state,float[] vp){
            this.scene=scene;this.hash=hash;aspectBits=Float.floatToRawIntBits(aspect);this.state=state.clone();this.vp=vp.clone();
        }
        boolean matches(Object scene,String hash,float aspect,float[] state,float[] vp){
            return this.scene==scene&&this.hash.equals(hash)&&aspectBits==Float.floatToRawIntBits(aspect)&&same(this.state,state)&&same(this.vp,vp);
        }
    }
}
