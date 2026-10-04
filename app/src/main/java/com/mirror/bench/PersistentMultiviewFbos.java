package com.mirror.bench;

/** GL-thread-owned framebuffer names. Texture storage remains owned by the caller. */
final class PersistentMultiviewFbos {
    interface Driver {
        int create();
        void bind(int framebuffer);
        /** Attach both textures at mip 0, base..base+3; require completeness and no GL errors. */
        void attach(int colorTexture,int depthTexture,int base);
        void delete(int framebuffer);
    }
    private final Driver driver;
    private final long generation;
    private final Thread thread=Thread.currentThread();
    private final int[] names;
    private boolean closed;
    private PersistentMultiviewFbos(Driver driver,long generation,int groups){this.driver=driver;this.generation=generation;names=new int[groups];}
    static PersistentMultiviewFbos create(Driver driver,long generation,int views,int color,int depth){
        if(driver==null||generation<=0||views<4||views>32||views%4!=0||color==0||depth==0)
            throw new IllegalArgumentException("Require a context generation, two textures and 4..32 layers divisible by four");
        PersistentMultiviewFbos result=new PersistentMultiviewFbos(driver,generation,views/4);
        try {
            for(int group=0;group<result.names.length;group++){
                int name=driver.create();if(name==0)throw new IllegalStateException("Framebuffer allocation returned zero");
                result.names[group]=name;driver.bind(name);driver.attach(color,depth,group*4);
            }
            return result;
        } catch(RuntimeException|Error failure){
            try{result.close(generation);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    int groups(){return names.length;}
    /** Caller increments generation at every onSurfaceCreated, even if numeric GL names are reused. */
    void bind(int base,long currentGeneration){
        requireThread();
        if(closed||generation!=currentGeneration)throw new IllegalStateException("Framebuffer owner is closed or context changed");
        if(base<0||base%4!=0||base/4>=names.length)throw new IllegalArgumentException("Invalid four-view base");
        driver.bind(names[base/4]);
    }
    /** Same context: delete all names. Lost context: discard names without issuing GL calls. */
    void close(long currentGeneration){
        requireThread();if(closed)return;closed=true;Throwable first=null;
        for(int i=0;i<names.length;i++){
            int name=names[i];names[i]=0;
            if(name!=0&&generation==currentGeneration)try{driver.delete(name);}catch(RuntimeException|Error failure){if(first==null)first=failure;else first.addSuppressed(failure);}
        }
        if(first instanceof RuntimeException)throw (RuntimeException)first;if(first instanceof Error)throw (Error)first;
    }
    private void requireThread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Framebuffer access requires its GL thread");}
}
