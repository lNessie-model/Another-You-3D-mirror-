package com.mirror.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual composition with narrow fake external dependencies; no native/device calls. */
public final class NpuExpressionPostGraphTest {
    static int checks;
    static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    interface Action { void run() throws Exception; }
    static Throwable failure(Action a){try{a.run();throw new AssertionError("Expected failure");}catch(AssertionError e){throw e;}catch(Throwable e){checks++;return e;}}
    static float[] xyz(){float[] a=new float[1434];for(int i=0;i<478;i++){a[i*3]=i/512f;a[i*3+1]=i%7/32f;a[i*3+2]=i/1024f;}return a;}
    static float[] pose(){float[] a=new float[16];a[0]=a[5]=a[10]=a[15]=1;a[12]=.1f;a[13]=.2f;a[14]=-.4f;return a;}
    static final class Clock implements NpuExpressionPostGraph.Clock {long now;public long nanoTime(){return now+=10;}}
    static final class Factory implements NpuExpressionPostGraph.Factory {
        final List<String> log=new ArrayList<>();final Geo geo=new Geo(this);final Expr expr=new Expr(this);
        Throwable geometryCreateFailure,expressionCreateFailure;int opens;float[] normalized=new float[292];
        public NpuExpressionPostGraph.Geometry createGeometry(){log.add("open-geometry");if(geometryCreateFailure!=null)throw (RuntimeException)geometryCreateFailure;opens++;return geo;}
        public NpuExpressionPostGraph.Expression createExpression()throws IOException{log.add("open-expression");if(expressionCreateFailure instanceof IOException)throw (IOException)expressionCreateFailure;if(expressionCreateFailure!=null)throw (RuntimeException)expressionCreateFailure;return expr;}
    }
    static final class Geo implements NpuExpressionPostGraph.Geometry {
        final Factory f;int calls,closes;RuntimeException processFailure,closeFailure;long timestamp;float[] input;CountDownLatch entered,release;
        Geo(Factory f){this.f=f;}
        public FaceGeometryPostGraph.Result process(float[] a,int w,int h,long t){f.log.add("geometry");calls++;input=a;timestamp=t;
            if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}
            if(processFailure!=null)throw processFailure;return new FaceGeometryPostGraph.Result(xyz(),pose());}
        public void close(){f.log.add("close-geometry");closes++;if(closeFailure!=null)throw closeFailure;}
    }
    static final class Expr implements NpuExpressionPostGraph.Expression {
        final Factory f;int calls,closes;RuntimeException runFailure,closeFailure,infoFailure;float[] weights=new float[52];float[] seen;
        Expr(Factory f){this.f=f;Arrays.fill(weights,.25f);}
        public float[] run(ByteBuffer input){f.log.add("expression");calls++;check(input.isDirect()&&input.position()==0&&input.remaining()==1168,"fixed direct input");seen=new float[292];for(int i=0;i<292;i++)seen[i]=input.getFloat(i*4);if(runFailure!=null)throw runFailure;return weights;}
        public String info(){f.log.add("info");if(infoFailure!=null)throw infoFailure;return "{\"scope\":\"fake mixed CPU/NPU metadata\"}";}
        public void close(){f.log.add("close-expression");closes++;if(closeFailure!=null)throw closeFailure;}
    }
    static NpuExpressionPostGraph create(Factory f)throws Exception{return new NpuExpressionPostGraph(f,(a,w,h)->{f.log.add("normalize");return f.normalized;},new Clock());}
    static void successAndOwnership()throws Exception{
        Factory f=new Factory();for(int i=0;i<292;i++)f.normalized[i]=i/300f;
        NpuExpressionPostGraph p=create(f);float[] in=xyz();float[][] out=p.process(in,640,480,7);
        check(f.log.equals(List.of("open-geometry","open-expression","info","geometry","normalize","expression")),"ordered whole pipeline");
        check(f.geo.timestamp==7&&f.geo.input==in,"same timestamp and coordinates routed");
        check(Arrays.equals(f.expr.seen,f.normalized),"no reorder or extra normalization");
        check(out.length==3&&out[0].length==52&&out[1].length==16&&out[2].length==1434,"complete 52 pose 478 transaction");
        f.expr.weights[0]=.9f;check(out[0][0]==.25f,"published output owns weights");
        check(p.summary().getLong("successful_frames")==1&&p.summary().getLong("expression_calls")==1,"success counters");
        check(p.summary().getDouble("geometry_total_ms")>0&&p.summary().getDouble("normalization_total_ms")>0,"CPU wall stages observed");
        check(p.summary().getString("timing_scope").contains("wall"),"no GPU timing claim");
        p.close();p.close();check(f.geo.closes==1&&f.expr.closes==1,"close exactly once");
        failure(()->p.process(in,640,480,8));failure(p::reset);
    }
    static void initializationFailures()throws Exception{
        Factory before=new Factory();RuntimeException absent=new IllegalStateException("geometry init");before.geometryCreateFailure=absent;
        check(failure(()->create(before))==absent&&before.expr.closes==0&&before.geo.closes==0,"failure before any owned resource");
        Factory f=new Factory();IOException original=new IOException("expression init");f.expressionCreateFailure=original;f.geo.closeFailure=new IllegalStateException("geometry cleanup");
        check(failure(()->create(f))==original,"init preserves original");check(f.geo.closes==1&&original.getSuppressed().length==1,"init cleans first resource");
        Factory info=new Factory();RuntimeException first=new IllegalStateException("info");info.expr.infoFailure=first;
        info.geo.closeFailure=new IllegalStateException("g close");info.expr.closeFailure=new IllegalStateException("e close");
        check(failure(()->create(info))==first,"info failure preserved");check(info.geo.closes==1&&info.expr.closes==1&&first.getSuppressed().length==2,"partial full init all cleanup");
    }
    static void realNormalizationUsesSmoothedOutput()throws Exception{
        Factory f=new Factory();for(int i=0;i<52;i++)f.expr.weights[i]=i/100f;
        NpuExpressionPostGraph p=new NpuExpressionPostGraph(f,new Clock());float[] raw=xyz();
        for(int i=0;i<478;i++){raw[3*i]=1-raw[3*i];raw[3*i+1]=-raw[3*i+1];}
        float[][] result=p.process(raw,480,640,2);
        float[] expected=NormalizedBlendshapeInput.normalizePixels(NormalizedBlendshapeInput.pixelCoordinates(xyz(),480,640).toArray()).toArray();
        for(int i=0;i<292;i++)check(Float.floatToRawIntBits(f.expr.seen[i])==Float.floatToRawIntBits(expected[i]),"real CPU normalization uses smoothed geometry, not original raw coordinates");
        check(Arrays.equals(result[0],f.expr.weights)&&Arrays.equals(result[1],pose())&&Arrays.equals(result[2],xyz()),"no expression reorder, pose transpose or landmark substitution");p.close();
        Factory invalid=new Factory();NpuExpressionPostGraph q=create(invalid);invalid.expr.weights[3]=1.01f;
        failure(()->q.process(xyz(),640,480,1));check(q.summary().getLong("successful_frames")==0,"out-of-range weights fail, never clipped");q.close();
    }
    static void stageFailuresAndReset()throws Exception{
        for(int stage=0;stage<4;stage++){
            Factory f=new Factory();RuntimeException expected=new IllegalStateException("stage "+stage);
            NpuExpressionPostGraph.Normalizer norm=(a,w,h)->{f.log.add("normalize");return f.normalized;};
            if(stage==0)f.geo.processFailure=expected;
            if(stage==1)norm=(a,w,h)->{throw expected;};
            if(stage==2)f.expr.runFailure=expected;
            if(stage==3)f.expr.weights[0]=Float.NaN;
            NpuExpressionPostGraph p=new NpuExpressionPostGraph(f,norm,new Clock());
            Throwable caught=failure(()->p.process(xyz(),640,480,1));if(stage<3)check(caught==expected,"same operation error");
            check(f.expr.calls==(stage>=2?1:0),"downstream not run after failure");
            check(f.geo.closes==1&&f.expr.closes==1,"both resources attempted after processing failure");
            check(p.summary().getLong("failed_frames")==1&&p.summary().getLong("successful_frames")==0,"no success on failure");
            failure(()->p.process(xyz(),640,480,2));p.close();check(f.geo.closes==1&&f.expr.closes==1,"failed resources not reclosed");
        }
        Factory f=new Factory();NpuExpressionPostGraph p=create(f);p.process(xyz(),640,480,1);p.reset();p.process(xyz(),480,640,1);
        check(f.opens==2&&p.summary().getLong("generation")==2&&p.summary().getLong("resets")==1,"reset rebuilds state and accepts new timestamp");p.close();
        Factory bad=new Factory();NpuExpressionPostGraph q=create(bad);bad.expressionCreateFailure=new IOException("reset init");failure(q::reset);
        check(bad.geo.closes==2&&bad.expr.closes==1,"reset partial init cleaned");failure(()->q.process(xyz(),640,480,1));q.close();
        Factory recover=new Factory();NpuExpressionPostGraph fresh=create(recover);recover.expr.runFailure=new IllegalStateException("native poison");
        failure(()->fresh.process(xyz(),640,480,5));recover.expr.runFailure=null;fresh.reset();fresh.process(xyz(),640,480,1);
        check(recover.opens==2&&fresh.summary().getLong("successful_frames")==1,"explicit reset rebuilds after a latched fault");fresh.close();
    }
    static void cleanupAndInputFailures()throws Exception{
        Factory f=new Factory();NpuExpressionPostGraph p=create(f);RuntimeException first=new IllegalStateException("op");f.expr.runFailure=first;
        f.geo.closeFailure=new IllegalStateException("g");f.expr.closeFailure=new IllegalStateException("e");
        check(failure(()->p.process(xyz(),640,480,1))==first&&first.getSuppressed().length==2,"operation primary plus both cleanup errors");
        Factory c=new Factory();NpuExpressionPostGraph close=create(c);c.geo.closeFailure=new IllegalStateException("g");c.expr.closeFailure=new IllegalStateException("e");
        Throwable error=failure(close::close);check(error.getSuppressed().length==1&&c.geo.closes==1&&c.expr.closes==1,"close attempts both");close.close();
        for(int which=0;which<4;which++){
            Factory b=new Factory();NpuExpressionPostGraph q=create(b);float[] input=xyz();int width=640;long t=1;
            if(which==0)input=new float[1431];if(which==1)input[4]=Float.NaN;if(which==2)width=320;if(which==3)t=Long.MAX_VALUE;
            final float[] a=input;final int w=width;final long stamp=t;failure(()->q.process(a,w,480,stamp));check(b.geo.calls==0&&b.expr.calls==0,"invalid input before external work");q.close();
        }
        for(float[] invalid:new float[][]{new float[291],new float[293],null}){
            Factory b=new Factory();NpuExpressionPostGraph q=new NpuExpressionPostGraph(b,(a,w,h)->invalid,new Clock());failure(()->q.process(xyz(),640,480,1));check(b.expr.calls==0,"invalid normalized result not submitted");q.close();
        }
        Factory same=new Factory();NpuExpressionPostGraph duplicate=create(same);duplicate.process(xyz(),640,480,5);
        failure(()->duplicate.process(xyz(),640,480,5));check(same.geo.calls==1,"duplicate timestamp rejected before graph packet");duplicate.close();
        Factory nonfinite=new Factory();nonfinite.normalized[100]=Float.POSITIVE_INFINITY;NpuExpressionPostGraph nf=create(nonfinite);
        failure(()->nf.process(xyz(),640,480,1));check(nonfinite.expr.calls==0,"nonfinite normalization never submitted");nf.close();
        float[] a=xyz(),pose=pose();FaceGeometryPostGraph.Result r=new FaceGeometryPostGraph.Result(a,pose);a[0]=99;pose[0]=99;float[] copy=r.landmarks();copy[0]=88;
        check(r.landmarks()[0]!=99&&r.landmarks()[0]!=88&&r.pose()[0]==1,"geometry result owned immutable copies");
        failure(()->new FaceGeometryPostGraph.Result(new float[1431],pose()));
    }
    static void serialization()throws Exception{
        Factory f=new Factory();NpuExpressionPostGraph p=create(f);f.geo.entered=new CountDownLatch(1);f.geo.release=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();Thread process=new Thread(()->{try{p.process(xyz(),640,480,1);}catch(Throwable t){error.set(t);}});process.start();check(f.geo.entered.await(2,TimeUnit.SECONDS),"process entered");
        CountDownLatch closed=new CountDownLatch(1);Thread closer=new Thread(()->{try{p.close();}catch(Throwable t){error.set(t);}finally{closed.countDown();}});closer.start();
        check(!closed.await(50,TimeUnit.MILLISECONDS)&&f.geo.closes==0,"close cannot race inference resources");f.geo.release.countDown();process.join(2000);closer.join(2000);
        check(!process.isAlive()&&!closer.isAlive()&&error.get()==null&&f.expr.calls==1&&f.expr.closes==1,"serialized completion before close");
    }
    public static void main(String[] args)throws Exception{successAndOwnership();realNormalizationUsesSmoothedOutput();initializationFailures();stageFailuresAndReset();cleanupAndInputFailures();serialization();System.out.println("NpuExpressionPostGraphTest: "+checks+" checks passed; fake dependencies, real composition; no native/ADB");}
}
