package com.mirror.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;

/** Serialized, opt-in composition. No queue, clipping, fabricated neutral result or fallback. */
final class NpuExpressionPostGraph implements AutoCloseable {
    interface Geometry extends AutoCloseable {
        FaceGeometryPostGraph.Result process(float[] xyz,int width,int height,long timestampMs);
        @Override void close();
    }
    interface Expression extends AutoCloseable {
        float[] run(ByteBuffer normalized);
        String info();
        @Override void close();
    }
    interface Factory {Geometry createGeometry();Expression createExpression()throws IOException;}
    interface Normalizer {float[] normalize(float[] smoothedXyz,int width,int height);}
    interface Clock {long nanoTime();}
    private final Factory factory;
    private final Normalizer normalizer;
    private final Clock clock;
    private final ByteBuffer input=ByteBuffer.allocateDirect(1168).order(ByteOrder.nativeOrder());
    private Geometry geometry;
    private Expression expression;
    private boolean closed;
    private Throwable fault;
    private String modelInfo;
    private long lastTimestamp=-1,generation,resets,attempts,successes,failures;
    private long geometryCalls,normalizationCalls,expressionCalls,validationCalls;
    private long geometryNs,normalizationNs,expressionNs,validationNs,totalNs;

    NpuExpressionPostGraph(String modelPath,String applicationApkPath,String metadataPath,boolean smoothing)throws IOException {
        this(new Factory(){
            public Geometry createGeometry(){return new LiveGeometry(metadataPath,smoothing);}
            public Expression createExpression()throws IOException{return new LiveExpression(modelPath,applicationApkPath);}
        },NpuExpressionPostGraph::normalize,System::nanoTime);
    }
    // Allocate the owner before constructing its native resource: no post-open adapter allocation.
    private static final class LiveGeometry implements Geometry {
        private final FaceGeometryPostGraph graph;
        LiveGeometry(String metadata,boolean smoothing){graph=new FaceGeometryPostGraph(metadata,smoothing);}
        public FaceGeometryPostGraph.Result process(float[] xyz,int w,int h,long t){return graph.process(xyz,w,h,t);}
        public void close(){graph.close();}
    }
    private static final class LiveExpression implements Expression {
        private final RknnExpression model;
        LiveExpression(String modelPath,String apkPath)throws IOException{model=new RknnExpression(modelPath,apkPath);}
        public float[] run(ByteBuffer input){return model.run(input);}
        public String info(){return model.info();}
        public void close(){model.close();}
    }
    // Tests exercise this actual composition; only external graph/native boundaries are replaced.
    NpuExpressionPostGraph(Factory factory,Clock clock)throws IOException {
        this(factory,NpuExpressionPostGraph::normalize,clock);
    }
    NpuExpressionPostGraph(Factory factory,Normalizer normalizer,Clock clock)throws IOException {
        if(factory==null||normalizer==null||clock==null)throw new IllegalArgumentException("Missing expression post dependency");
        this.factory=factory;this.normalizer=normalizer;this.clock=clock;open();
    }
    private static float[] normalize(float[] xyz,int width,int height){
        return NormalizedBlendshapeInput.normalizePixels(
            NormalizedBlendshapeInput.pixelCoordinates(xyz,width,height).toArray()).toArray();
    }
    private void open()throws IOException {
        try {
            geometry=factory.createGeometry();if(geometry==null)throw new IllegalStateException("Missing geometry graph");
            expression=factory.createExpression();if(expression==null)throw new IllegalStateException("Missing normalized expression model");
            String info=expression.info();if(info==null||info.isEmpty())throw new IllegalStateException("Missing normalized expression metadata");
            modelInfo=info;generation++;lastTimestamp=-1;fault=null;
        } catch(Throwable original){fault=original;Throwable error=release(original);if(error instanceof IOException)throw (IOException)error;throw FaceGeometryPostGraph.propagate(error);}
    }
    /** Caller owns input and must not mutate it until this synchronous call returns. */
    synchronized float[][] process(float[] xyz,int width,int height,long timestampMs) {
        requireReady();attempts++;long frameStart=clock.nanoTime();
        try {
            FaceGeometryPostGraph.validateRequest(xyz,width,height,timestampMs);
            if(timestampMs<=lastTimestamp)throw new IllegalArgumentException("Post timestamps must increase; reset for a new input session");
            lastTimestamp=timestampMs;
            FaceGeometryPostGraph.Result result;long start=clock.nanoTime();geometryCalls++;
            try {result=geometry.process(xyz,width,height,timestampMs);if(result==null)throw new IllegalStateException("Missing geometry result");}
            finally {geometryNs+=clock.nanoTime()-start;}
            float[] landmarks;start=clock.nanoTime();normalizationCalls++;
            try {
                landmarks=result.landmarks();float[] normalized=normalizer.normalize(landmarks,width,height);
                if(normalized==null||normalized.length!=292)throw new IllegalStateException("Invalid normalized expression shape");
                input.clear();
                for(int i=0;i<292;i++){
                    if(!Float.isFinite(normalized[i]))throw new IllegalStateException("Nonfinite normalized expression value");
                    input.putFloat(i*4,normalized[i]);
                }
            } finally {normalizationNs+=clock.nanoTime()-start;}
            float[] weights;start=clock.nanoTime();expressionCalls++;
            try {weights=expression.run(input);}
            finally {expressionNs+=clock.nanoTime()-start;}
            start=clock.nanoTime();validationCalls++;
            try {
                float[][] complete=BlendshapeSchema.copyValidatedPostOutput(weights,result.pose(),landmarks);
                successes++;return complete;
            } finally {validationNs+=clock.nanoTime()-start;}
        } catch(Throwable original){failures++;fault=original;throw FaceGeometryPostGraph.propagate(release(original));}
        finally {totalNs+=clock.nanoTime()-frameStart;}
    }
    /** Recreate graph/native state; statistics remain cumulative and generation increments on success. */
    synchronized void reset()throws IOException {
        if(closed)throw new IllegalStateException("Closed expression post processor");
        resets++;fault=new IllegalStateException("Expression post reset did not finish");
        Throwable error=release(null);
        if(error!=null){fault=error;throw FaceGeometryPostGraph.propagate(error);}
        open();
    }
    private void requireReady(){
        if(closed||fault!=null||geometry==null||expression==null)
            throw new IllegalStateException("Closed or failed normalized expression post; reset/recreate required",fault);
    }
    private Throwable release(Throwable original){
        Expression ownedExpression=expression;Geometry ownedGeometry=geometry;expression=null;geometry=null;
        ResourceCleanup cleanup=new ResourceCleanup(original);
        cleanup.close("normalized expression",ownedExpression);cleanup.close("canonical geometry",ownedGeometry);return cleanup.failure();
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;Throwable error=release(null);if(error!=null){fault=error;throw FaceGeometryPostGraph.propagate(error);}
    }
    synchronized JSONObject summary()throws org.json.JSONException {
        return new JSONObject().put("backend","Original CPU OneEuro/canonical pose + FP32 input normalization + mixed CPU/NPU expression suffix")
            .put("model_sha256",RknnExpression.MODEL_SHA256).put("model_info",modelInfo==null?JSONObject.NULL:new JSONObject(modelInfo))
            .put("model_info_scope","Last successfully opened context; retained after failure or close")
            .put("input_semantics","Official146 pixel XY -> original FP32 mean-radius normalized [1,146,2]")
            .put("timing_scope","Serial CPU wall-clock intervals; expression API includes mixed CPU/NPU fallback and waits; not GPU time, utilization or FPS")
            .put("scheduling","Synchronous serialized post transaction; no internal queue")
            .put("generation",generation).put("resets",resets).put("attempted_frames",attempts).put("successful_frames",successes).put("failed_frames",failures)
            .put("closed",closed).put("fault",fault==null?JSONObject.NULL:fault.toString())
            .put("geometry_calls",geometryCalls).put("normalization_calls",normalizationCalls).put("expression_calls",expressionCalls).put("validation_calls",validationCalls)
            .put("geometry_total_ms",geometryNs/1e6).put("normalization_total_ms",normalizationNs/1e6).put("expression_api_total_ms",expressionNs/1e6)
            .put("validation_total_ms",validationNs/1e6).put("total_wall_ms",totalNs/1e6)
            .put("geometry_mean_ms",average(geometryNs,geometryCalls)).put("normalization_mean_ms",average(normalizationNs,normalizationCalls))
            .put("expression_api_mean_ms",average(expressionNs,expressionCalls)).put("total_mean_ms",average(totalNs,attempts))
            .put("statistics_scope","Cumulative since construction, includes failed-stage attempts; total includes error cleanup; reset/init costs excluded");
    }
    private static Object average(long nanos,long count){return count==0?JSONObject.NULL:nanos/count/1e6;}
}
