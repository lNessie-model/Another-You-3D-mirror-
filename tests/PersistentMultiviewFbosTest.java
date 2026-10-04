package com.mirror.bench;

import java.util.*;

/** Resource and command contract, not a GLES emulation or pixel test. */
public final class PersistentMultiviewFbosTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void rejects(Runnable action){try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    private static final class Fake implements PersistentMultiviewFbos.Driver {
        int next=10,bound,attachCalls,failAt=-1,deleted;
        final List<String> log=new ArrayList<>();final Set<Integer> live=new HashSet<>();
        public int create(){int id=next++;live.add(id);log.add("create:"+id);return id;}
        public void bind(int id){bound=id;log.add("bind:"+id);}
        public void attach(int color,int depth,int base){attachCalls++;log.add("attach:"+bound+":"+color+":"+depth+":"+base);if(base==failAt)throw new IllegalStateException("incomplete");}
        public void delete(int id){check(live.remove(id));deleted++;log.add("delete:"+id);}
    }
    public static void main(String[] args)throws Exception {
        Fake gl=new Fake();PersistentMultiviewFbos group=PersistentMultiviewFbos.create(gl,7,20,101,102);
        check(group.groups()==5&&gl.attachCalls==5&&gl.live.size()==5);
        for(int i=0;i<5;i++)check(gl.log.contains("attach:"+(10+i)+":101:102:"+(i*4)));
        int attachments=gl.attachCalls;
        for(int frame=0;frame<200;frame++)for(int base=0;base<20;base+=4)group.bind(base,7);
        check(gl.attachCalls==attachments);check(gl.bound==14);
        rejects(()->group.bind(1,7));rejects(()->group.bind(20,7));rejects(()->group.bind(-4,7));rejects(()->group.bind(0,8));
        final boolean[] wrongThread={false};Thread t=new Thread(()->{try{group.bind(0,7);}catch(IllegalStateException expected){wrongThread[0]=true;}});t.start();t.join();check(wrongThread[0]);
        group.close(7);check(gl.live.isEmpty()&&gl.deleted==5);group.close(7);check(gl.deleted==5);rejects(()->group.bind(0,7));
        // Resize: delete every old framebuffer before attachment storage is replaced by the caller.
        PersistentMultiviewFbos replacement=PersistentMultiviewFbos.create(gl,7,16,201,202);
        check(replacement.groups()==4&&gl.live.size()==4);replacement.close(7);check(gl.live.isEmpty());
        // Context loss destroys old GL objects externally. Never delete those numeric names in a new context.
        PersistentMultiviewFbos lost=PersistentMultiviewFbos.create(gl,8,20,301,302);int before=gl.deleted;
        lost.close(9);check(gl.deleted==before);rejects(()->lost.bind(0,9));
        for(int failedBase:new int[]{0,4,16}){Fake bad=new Fake();bad.failAt=failedBase;rejects(()->PersistentMultiviewFbos.create(bad,1,20,1,2));check(bad.live.isEmpty());check(bad.deleted==failedBase/4+1);}
        for(int views:new int[]{0,1,3,6,33,Integer.MAX_VALUE})rejects(()->PersistentMultiviewFbos.create(new Fake(),1,views,1,2));
        rejects(()->PersistentMultiviewFbos.create(new Fake(),0,20,1,2));rejects(()->PersistentMultiviewFbos.create(new Fake(),1,20,0,2));
        Fake maximum=new Fake();PersistentMultiviewFbos all=PersistentMultiviewFbos.create(maximum,1,32,1,2);check(all.groups()==8);all.close(1);
        System.out.println("PersistentMultiviewFbosTest: "+checks+" checks passed");
    }
}
