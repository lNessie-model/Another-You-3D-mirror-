package com.mirror.bench;

import org.json.*;

/** Host contract checks: no OVR timer; these do not assert real driver compatibility. */
public final class RuntimeGpuFinalProfileTest {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static RuntimeGpuProfile create(boolean enabled,long gen,RuntimeGpuProfile previous,GpuTimerProbeTest.Driver d,GpuTimerProbeTest.Clock c,int every){
        return new RuntimeGpuProfile(enabled,gen,previous,d,c,every);
    }
    static void capture(RuntimeGpuProfile p,long id,SceneViewSettings scene,long epoch,int target,boolean face){
        p.captureFrameScope(id,epoch,target,face,scene,1200,1920,400,640,16);
    }
    static void frame(RuntimeGpuProfile p,SceneViewSettings scene,long epoch,int target,boolean face){
        long id=p.nextCallback();capture(p,id,scene,epoch,target,face);p.beginFinal(id,true);p.endFinal();p.finishCallback();
    }
    static JSONObject published(RuntimeGpuProfile p,GpuTimerProbeTest.Clock c)throws Exception{c.step(1_000_000_000L);p.finishCallback();return new JSONObject(p.statusJson());}
    public static void main(String[] args)throws Exception {
        var d=new GpuTimerProbeTest.Driver();var c=new GpuTimerProbeTest.Clock();var p=create(true,1,null,d,c,8);
        for(int i=1;i<=24;i++){long id=p.nextCallback();capture(p,id,SceneViewSettings.DEFAULT,1,31,true);p.finishCallback();}
        ok(d.begins==0&&d.ends==0&&d.availability==0&&d.reads==0);
        JSONObject noViews=published(p,c);ok(noViews.getString("views_gpu_status").equals("unsupported_ovr_multiview"));
        ok(noViews.getJSONArray("events").length()==0&&noViews.getInt("pending_queries")==0);p.close();

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,2,null,d,c,8);
        for(int i=1;i<=24;i++){frame(p,SceneViewSettings.DEFAULT,1,31,true);ok(d.begins==i/8&&d.ends==i/8);d.readyAll();c.step(5_000_000);}
        p.nextCallback();JSONObject sampled=published(p,c);ok(d.begins==3&&d.ends==3&&d.allocated==16);
        JSONArray events=sampled.getJSONArray("events");ok(events.length()==3);
        for(int i=0;i<events.length();i++){JSONObject e=events.getJSONObject(i);ok(e.getString("stage").equals("INTERLACE_WITH_BACKGROUND"));ok(e.getLong("callback")==8L*(i+1));ok(e.getString("status").equals("VALID"));}
        for(int i=0;i<sampled.getJSONArray("stages").length();i++){JSONObject s=sampled.getJSONArray("stages").getJSONObject(i);if(!s.getString("stage").equals("INTERLACE_WITH_BACKGROUND"))ok(s.getLong("valid_count")==0&&s.isNull("mean_gpu_ns"));}
        ok(sampled.getString("cpu_clock_domain").equals("java_system_nanotime_android_clock_monotonic"));p.close();

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,3,null,d,c,1);
        long id=p.nextCallback();capture(p,id,SceneViewSettings.DEFAULT,2,31,true);
        try{p.beginFinal(id,false);throw new AssertionError("Nondefault framebuffer accepted");}catch(IllegalArgumentException expected){ok(d.begins==0&&d.ends==0);}
        p.beginFinal(id,true);p.endFinal();ok(d.begins==1&&d.ends==1);p.close();
        for(var method:RuntimeGpuProfile.class.getMethods())ok(!method.getName().equals("beginViews")&&!method.getName().equals("endViews")&&!method.getName().equals("beginViewGroup"));

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,4,null,d,c,1);
        frame(p,SceneViewSettings.DEFAULT,2,31,true);d.readyAll();c.step(5_000_000);
        frame(p,SceneViewSettings.DEFAULT.withBackground(9),3,10,false);d.readyAll();c.step(5_000_000);p.nextCallback();
        JSONObject scoped=published(p,c);JSONArray scopes=scoped.getJSONArray("scopes");ok(scopes.length()==2);
        ok(scopes.getJSONObject(0).getInt("target_fps")==31&&scopes.getJSONObject(0).getBoolean("face_active"));
        ok(scopes.getJSONObject(1).getInt("target_fps")==10&&!scopes.getJSONObject(1).getBoolean("face_active"));
        ok(scopes.getJSONObject(1).getJSONObject("scene_view").getInt("background")==9);
        ok(scoped.getJSONArray("events").getJSONObject(0).getLong("scope_id")!=scoped.getJSONArray("events").getJSONObject(1).getLong("scope_id"));
        final RuntimeGpuProfile shared=p;final String[] received={null};int before=d.availability;
        Thread reader=new Thread(()->received[0]=shared.statusJson());reader.start();reader.join();ok(received[0].equals(p.statusJson())&&d.availability==before);
        final boolean[] rejected={false};reader=new Thread(()->{try{shared.nextCallback();}catch(IllegalStateException expected){rejected[0]=true;}});reader.start();reader.join();ok(rejected[0]);p.close();

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,5,null,d,c,1);frame(p,SceneViewSettings.DEFAULT,1,31,true);
        var newer=create(true,6,p,new GpuTimerProbeTest.Driver(),c,1);JSONObject replaced=new JSONObject(newer.statusJson());
        ok(d.deletes==0&&replaced.getInt("previous_pending_discarded_or_last_published")==1&&!replaced.getBoolean("previous_pending_unknown"));newer.close();
        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,7,null,d,c,1);frame(p,SceneViewSettings.DEFAULT,1,31,true);
        final RuntimeGpuProfile previous=p;final var clock=c;final String[] other={null};Thread replacement=new Thread(()->{var next=create(true,8,previous,new GpuTimerProbeTest.Driver(),clock,1);other[0]=next.statusJson();next.close();});replacement.start();replacement.join();
        ok(new JSONObject(other[0]).getBoolean("previous_pending_unknown")&&d.deletes==0);p.abandonForReplacement();

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,9,null,d,c,1);id=p.nextCallback();capture(p,id,SceneViewSettings.DEFAULT,1,31,true);p.beginFinal(id,true);d.endGl=0x502;
        var original=new RuntimeException("original draw failure");p.abort(original);ok(original.getSuppressed().length==1);ok(new JSONObject(p.statusJson()).getLong("aborted_callbacks")==1);p.close();
        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,10,null,d,c,1);d.deleteGl=0x505;
        try{p.close();throw new AssertionError("Close swallowed GL failure");}catch(GpuTimerProbe.GlFailure expected){ok(expected.errorCode==0x505);}
        ok(new JSONObject(p.statusJson()).getBoolean("closed"));

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,11,null,d,c,1);
        for(int i=0;i<150;i++){frame(p,SceneViewSettings.DEFAULT,i,31,true);d.readyAll();c.step(5_000_000);}
        JSONObject bounded=published(p,c);ok(bounded.getJSONArray("scopes").length()==128&&bounded.getLong("scope_history_overwritten")>0);ok(bounded.getJSONArray("events").length()<=128);p.close();
        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(true,12,null,d,c,1);
        for(int i=0;i<150;i++)frame(p,SceneViewSettings.DEFAULT,i,31,true);
        ok(d.allocated==16&&d.begins==16);d.readyAll();p.nextCallback();JSONObject lost=published(p,c);ok(lost.getInt("missing_scope_metadata_events")>=16);p.close();

        d=new GpuTimerProbeTest.Driver();c=new GpuTimerProbeTest.Clock();p=create(false,13,null,d,c,1);frame(p,SceneViewSettings.DEFAULT,1,31,true);p.close();
        ok(d.allocated==0&&d.begins==0&&d.ends==0&&d.disjointCalls==0);ok(!new JSONObject(p.statusJson()).getBoolean("requested"));
        p=new RuntimeGpuProfile(false,14);ok(!new JSONObject(p.statusJson()).getBoolean("requested"));p.close();
        System.out.println("RuntimeGpuFinalProfileTest PASS "+checks+" checks");
    }
}
