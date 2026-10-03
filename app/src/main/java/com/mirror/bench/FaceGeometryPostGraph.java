package com.mirror.bench;

import com.google.mediapipe.framework.Graph;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketCreator;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.tasks.vision.facegeometry.proto.FaceGeometryProto.FaceGeometry;
import com.google.mediapipe.tasks.vision.facegeometry.proto.FaceGeometryGraphOptionsProto.FaceGeometryGraphOptions;
import com.google.mediapipe.tasks.vision.facegeometry.calculators.proto.FaceGeometryPipelineCalculatorOptionsProto.FaceGeometryPipelineCalculatorOptions;
import com.google.mediapipe.tasks.core.proto.ExternalFileProto.ExternalFile;
import com.google.mediapipe.proto.CalculatorProto.CalculatorGraphConfig;
import com.google.mediapipe.proto.CalculatorOptionsProto.CalculatorOptions;

/** Original OneEuro/478/canonical-pose path with the CPU expression network removed. */
final class FaceGeometryPostGraph implements AutoCloseable {
    static final class Result {
        private final float[] xyz,transform;
        Result(float[] landmarks,float[] pose) {xyz=copyFinite(landmarks,1434,"landmarks");transform=copyFinite(pose,16,"pose");}
        float[] landmarks(){return xyz.clone();}
        float[] pose(){return transform.clone();}
    }
    private Graph graph;
    private PacketCreator creator;
    private volatile Result output;
    private volatile Throwable outputError;
    private Throwable fault;
    private long lastTimestamp=-1;

    FaceGeometryPostGraph(String metadataPath,boolean smoothing) {
        CalculatorGraphConfig config=configuration(metadataPath,smoothing);
        System.loadLibrary("mediapipe_tasks_jni");
        Graph g=new Graph();
        try {
            g.loadBinaryGraph(config);
            g.addMultiStreamCallback(java.util.List.of("geometry","landmarks"),packets->{
                try {
                    if(packets.size()!=2)throw new IllegalArgumentException("Incomplete geometry/landmark packets");
                    var geometries=PacketGetter.getProtoVector(packets.get(0),FaceGeometry.parser());
                    if(geometries.size()!=1)throw new IllegalArgumentException("Expected one face geometry");
                    float[] pose=FacePoseMatrix.copyValidated(geometries.get(0).getPoseTransformMatrix());
                    var points=PacketGetter.getProto(packets.get(1),NormalizedLandmarkList.parser());
                    if(points.getLandmarkCount()!=478)throw new IllegalArgumentException("Expected 478 smoothed landmarks");
                    float[] coords=new float[1434];
                    for(int i=0;i<478;i++){
                        coords[i*3]=points.getLandmark(i).getX();coords[i*3+1]=points.getLandmark(i).getY();coords[i*3+2]=points.getLandmark(i).getZ();
                    }
                    output=new Result(coords,pose);
                } catch(Throwable invalid){output=null;outputError=invalid;}
            },false);
            g.startRunningGraph();
            PacketCreator ready=new PacketCreator(g);graph=g;creator=ready;
        } catch(Throwable original) {
            ResourceCleanup cleanup=new ResourceCleanup(original);cleanup.close("geometry teardown",g::tearDown);
            throw propagate(cleanup.failure());
        }
    }
    // Pure protobuf configuration is inspected by host tests without opening the native graph.
    static CalculatorGraphConfig configuration(String metadataPath,boolean smoothing) {
        if(metadataPath==null||metadataPath.isEmpty())throw new IllegalArgumentException("Missing canonical geometry metadata");
        var geometry=FaceGeometryGraphOptions.newBuilder().setGeometryPipelineOptions(
                FaceGeometryPipelineCalculatorOptions.newBuilder().setMetadataFile(ExternalFile.newBuilder().setFileName(metadataPath))).build();
        return CalculatorGraphConfig.newBuilder().setNumThreads(2)
            .addInputStream("coords").addInputStream("size").addOutputStream("geometry").addOutputStream("landmarks")
            .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("TensorConverterCalculator")
                .addInputStream("MATRIX:coords").addOutputStream("TENSORS:tensors"))
            .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("TensorsToLandmarksCalculator")
                .addInputStream("TENSORS:tensors").addOutputStream("NORM_LANDMARKS:raw_landmarks").setOptions(landmarkOptions()))
            .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("LandmarksSmoothingCalculator")
                .addInputStream("NORM_LANDMARKS:raw_landmarks").addInputStream("IMAGE_SIZE:size")
                .addOutputStream("NORM_FILTERED_LANDMARKS:landmarks").setOptions(smoothingOptions(smoothing)))
            .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("ConcatenateNormalizedLandmarkListVectorCalculator")
                .addInputStream("landmarks").addOutputStream("landmark_vector"))
            .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("mediapipe.tasks.vision.face_geometry.FaceGeometryFromLandmarksGraph")
                .addInputStream("FACE_LANDMARKS:landmark_vector").addInputStream("IMAGE_SIZE:size").addOutputStream("FACE_GEOMETRY:geometry")
                .setOptions(CalculatorOptions.newBuilder().setExtension(FaceGeometryGraphOptions.ext,geometry))).build();
    }
    synchronized Result process(float[] xyz,int width,int height,long timestampMs) {
        if(graph==null)throw new IllegalStateException("Closed or failed geometry graph",fault);
        try {
            validateRequest(xyz,width,height,timestampMs);
            if(timestampMs<=lastTimestamp)throw new IllegalArgumentException("Geometry timestamps must increase");
            lastTimestamp=timestampMs;output=null;outputError=null;
            Packet lm=creator.createMatrix(1,xyz.length,xyz);
            try(ResourceCleanup.Release releaseLandmarks=lm::release){
                Packet size=creator.createInt32Pair(width,height);
                try(ResourceCleanup.Release releaseSize=size::release){
                    graph.addPacketToInputStream("coords",lm,timestampMs*1000);
                    graph.addPacketToInputStream("size",size,timestampMs*1000);
                    graph.waitUntilGraphIdle();
                }
            }
            if(outputError!=null)throw propagate(outputError);
            Result result=output;
            if(result==null)throw new IllegalStateException("Incomplete smoothed landmark/canonical pose outputs");
            return result;
        } catch(Throwable original){fault=original;throw propagate(releaseGraph(original));}
    }
    static void validateRequest(float[] xyz,int width,int height,long timestampMs){
        if(xyz==null||xyz.length!=1434)throw new IllegalArgumentException("Expected 478 XYZ input landmarks");
        for(float value:xyz)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite geometry input");
        if(!((width==640&&height==480)||(width==480&&height==640)))throw new IllegalArgumentException("Unsupported oriented image dimensions");
        if(timestampMs<0||timestampMs>Long.MAX_VALUE/1000)throw new IllegalArgumentException("Invalid geometry timestamp");
    }
    private static float[] copyFinite(float[] values,int count,String label){
        if(values==null||values.length!=count)throw new IllegalArgumentException("Incomplete geometry "+label);
        float[] copy=values.clone();for(float value:copy)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite geometry "+label);return copy;
    }
    private Throwable releaseGraph(Throwable primary){
        Graph owned=graph;graph=null;creator=null;output=null;outputError=null;
        if(owned==null)return primary;
        ResourceCleanup cleanup=new ResourceCleanup(primary);
        // Do not wait for end-of-stream if closing packet sources itself failed.
        cleanup.close("geometry drain",()->{owned.closeAllPacketSources();owned.waitUntilGraphDone();});
        cleanup.close("geometry teardown",owned::tearDown);return cleanup.failure();
    }
    static RuntimeException propagate(Throwable error){
        if(error instanceof Error)throw (Error)error;
        if(error instanceof RuntimeException)return (RuntimeException)error;
        return new IllegalStateException(error);
    }
    @Override public synchronized void close(){Throwable failure=releaseGraph(null);if(failure!=null)throw propagate(failure);}
    private static CalculatorOptions landmarkOptions() {
        // Official tensors_to_landmarks_calculator.proto: ext=335742640, num_landmarks=1, width=2, height=3.
        // Java classes for these calculator options are absent from this Tasks AAR; unknown fields survive parseFrom.
        try {
            var bytes=new java.io.ByteArrayOutputStream();
            var fields=com.google.protobuf.CodedOutputStream.newInstance(bytes);
            fields.writeInt32(1,478); fields.writeInt32(2,1); fields.writeInt32(3,1); fields.flush();
            var envelope=new java.io.ByteArrayOutputStream();
            var options=com.google.protobuf.CodedOutputStream.newInstance(envelope);
            options.writeByteArray(335742640,bytes.toByteArray()); options.flush();
            return CalculatorOptions.parseFrom(envelope.toByteArray());
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    }
    private static CalculatorOptions smoothingOptions(boolean enabled) {
        // Official landmarks_smoothing_calculator.proto: ext=325671429; same face graph OneEuro parameters.
        try {
            var euro=new java.io.ByteArrayOutputStream();
            var values=com.google.protobuf.CodedOutputStream.newInstance(euro);
            values.writeFloat(2,.05f); values.writeFloat(3,80); values.writeFloat(4,1); values.flush();
            var filter=new java.io.ByteArrayOutputStream();
            var fields=com.google.protobuf.CodedOutputStream.newInstance(filter);
            fields.writeByteArray(enabled?3:1,enabled?euro.toByteArray():new byte[0]); fields.flush();
            var envelope=new java.io.ByteArrayOutputStream();
            var options=com.google.protobuf.CodedOutputStream.newInstance(envelope);
            options.writeByteArray(325671429,filter.toByteArray()); options.flush();
            return CalculatorOptions.parseFrom(envelope.toByteArray());
        } catch(java.io.IOException error) { throw new IllegalStateException(error); }
    }
}
