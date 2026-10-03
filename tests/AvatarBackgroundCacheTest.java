package com.mirror.bench;

import java.util.ArrayList;
import java.util.List;

/** Exercises the real cache controller using a recording resource driver; does not execute GL. */
public final class AvatarBackgroundCacheTest {
    private static int checks;
    private static final String HASH="a".repeat(64);
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    private static void rejects(Runnable action,String reason){boolean rejected=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,reason);}
    private static float[] identity(){float[] a=new float[16];a[0]=a[5]=a[10]=a[15]=1;return a;}
    private static float[] cameras(int views){float[] a=new float[views*16];for(int i=0;i<views;i++)System.arraycopy(identity(),0,a,i*16,16);return a;}
    private static final class Driver implements AvatarBackgroundCache.Driver {
        final List<String> calls=new ArrayList<>();int failBase=-1;boolean failAllocate,failFinish,failRelease;
        public void allocate(int width,int height,int views,int group){calls.add("allocate:"+views+":"+group);if(failAllocate)throw new IllegalStateException("partial allocation");}
        public void capture(int base,int group,float[] vp,int offset,float aspect){calls.add("capture:"+base);check(offset==base*16,"Each view group uses its own camera matrices");if(base==failBase)throw new IllegalStateException("incomplete group");}
        public void finishCapture(){calls.add("finish");if(failFinish)throw new IllegalStateException("GPU submission failure");}
        public void restore(int base,int group){calls.add("restore:"+base);}
        public void release(){calls.add("release");if(failRelease)throw new IllegalStateException("cleanup failure");}
    }
    public static void main(String[] args)throws Exception {
        for(int views:new int[]{16,20})for(int group:new int[]{1,4}){
            Driver driver=new Driver();Object scene=new Object();float[] state=identity(),vp=cameras(views);
            AvatarBackgroundCache cache=new AvatarBackgroundCache(driver,1,400,640,views,group);
            check(cache.storageBytes()==400L*640*views*6,"RGBA8 plus DEPTH16 memory budget");
            rejects(()->cache.restoreGroup(1,0),"No partially captured cache may be restored");
            check(cache.prepare(1,scene,HASH,.625f,state,vp),"First frame captures all background views");
            check(driver.calls.size()==views/group+2,"Exactly one allocation, complete groups and finish");
            int before=driver.calls.size();
            check(!cache.prepare(1,scene,HASH,.625f,state,vp),"Unchanged frame hits cache");
            check(driver.calls.size()==before,"A hit issues no allocation, clear or background geometry draw");
            for(int base=0;base<views;base+=group)cache.restoreGroup(1,base);
            check(driver.calls.size()==before+views/group,"Every frame restores every view group once");
            rejects(()->cache.restoreGroup(2,0),"Context generation must match at restore");
            rejects(()->cache.restoreGroup(1,views),"Reject out-of-range group");
            if(group==4)rejects(()->cache.restoreGroup(1,1),"Reject unaligned OVR4 group");
            vp[16]=Math.nextUp(vp[16]);
            check(cache.prepare(1,scene,HASH,.625f,state,vp),"One camera ULP invalidates the complete cache");
            state[12]=-.0f;
            check(cache.prepare(1,scene,HASH,.625f,state,vp),"Actual static transform bits include signed zero");
            check(cache.prepare(1,scene,"b".repeat(64),.625f,state,vp),"Asset hash change invalidates cache");
            check(cache.prepare(1,new Object(),"b".repeat(64),.625f,state,vp),"New scene identity invalidates even with same asset hash");
            check(cache.builds()==5,"Count only complete cache builds");
            cache.close(1);int deleted=driver.calls.size();cache.close(1);
            check(driver.calls.size()==deleted,"Same-context close is idempotent");
            rejects(()->cache.restoreGroup(1,0),"Closed resources cannot render");
        }
        for(int fail:new int[]{0,1,2}){
            Driver driver=new Driver();driver.failAllocate=fail==0;driver.failBase=fail==1?4:-1;driver.failFinish=fail==2;driver.failRelease=true;
            AvatarBackgroundCache cache=new AvatarBackgroundCache(driver,7,400,640,20,4);
            boolean rejected=false;try{cache.prepare(7,new Object(),HASH,.625f,identity(),cameras(20));}catch(IllegalStateException expected){rejected=true;check(expected.getSuppressed().length==1,"Cleanup failure preserves original capture failure");}
            check(rejected,"Partial allocation, failed middle group and unfinished submission are rejected");
            check(driver.calls.get(driver.calls.size()-1).equals("release"),"Failed capture releases partial resources");
            check(cache.builds()==0,"Failed capture cannot become a qualified build");
            rejects(()->cache.restoreGroup(7,0),"Failure never publishes a partially captured cache");
        }
        Driver driver=new Driver();AvatarBackgroundCache cache=new AvatarBackgroundCache(driver,3,400,640,16,4);
        final Throwable[] wrongThread=new Throwable[1];Thread thread=new Thread(()->{try{cache.prepare(3,new Object(),HASH,.625f,identity(),cameras(16));}catch(Throwable expected){wrongThread[0]=expected;}});thread.start();thread.join();
        check(wrongThread[0] instanceof IllegalStateException&&driver.calls.isEmpty(),"Cross-thread access cannot touch GPU driver");
        rejects(()->cache.close(4),"Never delete a lost context's numeric names in a new context");
        check(driver.calls.isEmpty(),"Wrong-context cleanup issues no GPU calls");
        rejects(()->new AvatarBackgroundCache(new Driver(),1,4096,4096,32,4),"Bound cache GPU storage to 64 MiB");
        rejects(()->new AvatarBackgroundCache(new Driver(),1,400,640,18,4),"OVR group count must cover all views");
        rejects(()->new AvatarBackgroundCache(new Driver(),1,400,640,20,2),"Only serial and OVR4 backends supported");
        float[] invalid=cameras(16);invalid[4]=Float.NaN;
        rejects(()->cache.prepare(3,new Object(),HASH,.625f,identity(),invalid),"Reject nonfinite camera matrices");
        check(!driver.calls.contains("allocate:16:4"),"Invalid cache key cannot allocate GPU memory");
        System.out.println("AvatarBackgroundCacheTest: "+checks+" checks passed; resource/key/controller behavior only, no EGL or FPS qualification");
    }
}
