package com.mirror.bench;

import com.google.mediapipe.framework.Graph;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketCreator;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.formats.proto.ClassificationProto.ClassificationList;
import com.google.mediapipe.tasks.vision.facegeometry.proto.FaceGeometryProto.FaceGeometry;
import com.google.mediapipe.tasks.vision.facegeometry.proto.FaceGeometryGraphOptionsProto.FaceGeometryGraphOptions;
import com.google.mediapipe.tasks.vision.facegeometry.calculators.proto.FaceGeometryPipelineCalculatorOptionsProto.FaceGeometryPipelineCalculatorOptions;
import com.google.mediapipe.tasks.vision.facelandmarker.proto.FaceBlendshapesGraphOptionsProto.FaceBlendshapesGraphOptions;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.BaseOptionsUtils;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.core.proto.ExternalFileProto.ExternalFile;
import com.google.mediapipe.proto.CalculatorProto.CalculatorGraphConfig;
import com.google.mediapipe.proto.CalculatorOptionsProto.CalculatorOptions;

/** Preserve Google's CPU blendshape network and canonical-face pose solver. */
final class FacePostGraph implements AutoCloseable {
    private Graph graph;
    private PacketCreator creator;
    private volatile float[][] output;
    private volatile String outputError;
    FacePostGraph(String blendPath,String metadataPath,boolean smoothing) {
        System.loadLibrary("mediapipe_tasks_jni");
        Graph g=new Graph();
        try {
            var blend=FaceBlendshapesGraphOptions.newBuilder().setBaseOptions(BaseOptionsUtils.convertBaseOptionsToProto(
                    BaseOptions.builder().setModelAssetPath(blendPath).setDelegate(Delegate.CPU).build())).build();
            var geometry=FaceGeometryGraphOptions.newBuilder().setGeometryPipelineOptions(
                    FaceGeometryPipelineCalculatorOptions.newBuilder().setMetadataFile(
                            ExternalFile.newBuilder().setFileName(metadataPath))).build();
            var config=CalculatorGraphConfig.newBuilder().setNumThreads(2)
                    .addInputStream("coords").addInputStream("size").addOutputStream("blend").addOutputStream("geometry").addOutputStream("landmarks")
                    // Tasks omits the dynamic Landmark proto factory. Its registered Matrix converter preserves floats exactly.
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("TensorConverterCalculator")
                            .addInputStream("MATRIX:coords").addOutputStream("TENSORS:tensors"))
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("TensorsToLandmarksCalculator")
                            .addInputStream("TENSORS:tensors").addOutputStream("NORM_LANDMARKS:raw_landmarks").setOptions(landmarkOptions()))
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("LandmarksSmoothingCalculator")
                            .addInputStream("NORM_LANDMARKS:raw_landmarks").addInputStream("IMAGE_SIZE:size")
                            .addOutputStream("NORM_FILTERED_LANDMARKS:landmarks").setOptions(smoothingOptions(smoothing)))
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("ConcatenateNormalizedLandmarkListVectorCalculator")
                            .addInputStream("landmarks").addOutputStream("landmark_vector"))
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("mediapipe.tasks.vision.face_landmarker.FaceBlendshapesGraph")
                            .addInputStream("LANDMARKS:landmarks").addInputStream("IMAGE_SIZE:size").addOutputStream("BLENDSHAPES:blend")
                            .setOptions(CalculatorOptions.newBuilder().setExtension(FaceBlendshapesGraphOptions.ext,blend)))
                    .addNode(CalculatorGraphConfig.Node.newBuilder().setCalculator("mediapipe.tasks.vision.face_geometry.FaceGeometryFromLandmarksGraph")
                            .addInputStream("FACE_LANDMARKS:landmark_vector").addInputStream("IMAGE_SIZE:size").addOutputStream("FACE_GEOMETRY:geometry")
                            .setOptions(CalculatorOptions.newBuilder().setExtension(FaceGeometryGraphOptions.ext,geometry))).build();
            g.loadBinaryGraph(config);
            g.addMultiStreamCallback(java.util.List.of("blend","geometry","landmarks"),packets->{
                try {
                    Packet packet=packets.get(0);
                    var rows=PacketGetter.getProto(packet,ClassificationList.parser());
                    float[] out=new float[rows.getClassificationCount()];
                    if(out.length!=BlendshapeSchema.SIZE) throw new IllegalArgumentException("Expected 52 named blendshapes");
                    for(int i=0;i<out.length;i++) {
                        var row=rows.getClassification(i);
                        BlendshapeSchema.validateClassification(i,row.getLabel(),row.hasIndex()?row.getIndex():i);
                        if(!Float.isFinite(row.getScore())) throw new IllegalArgumentException("Nonfinite blendshape score");
                        out[i]=row.getScore();
                    }
                    packet=packets.get(1);
                    var geometries=PacketGetter.getProtoVector(packet,FaceGeometry.parser());
                    if(geometries.size()!=1)throw new IllegalArgumentException("Expected one face geometry");
                    var matrix=geometries.get(0).getPoseTransformMatrix();
                    float[] transform=FacePoseMatrix.copyValidated(matrix);
                    var points=PacketGetter.getProto(packets.get(2),NormalizedLandmarkList.parser());
                    float[] coords=new float[points.getLandmarkCount()*3];
                    for(int i=0;i<points.getLandmarkCount();i++) {
                        coords[i*3]=points.getLandmark(i).getX(); coords[i*3+1]=points.getLandmark(i).getY(); coords[i*3+2]=points.getLandmark(i).getZ();
                    }
                    output=BlendshapeSchema.copyValidatedPostOutput(out,transform,coords);
                } catch(RuntimeException invalid) { output=null; outputError=invalid.toString(); }
            },false);
            g.startRunningGraph(); graph=g; creator=new PacketCreator(g);
        } catch(Throwable error) { g.tearDown(); throw error; }
    }
    float[][] process(float[] xyz,int width,int height,long timestampMs) {
        output=null; outputError=null;
        Packet lm=creator.createMatrix(1,xyz.length,xyz);
        try(ResourceCleanup.Release releaseLandmarks=lm::release) {
            Packet size=creator.createInt32Pair(width,height);
            try(ResourceCleanup.Release releaseSize=size::release) {
                graph.addPacketToInputStream("coords",lm,timestampMs*1000);
                graph.addPacketToInputStream("size",size,timestampMs*1000);
                graph.waitUntilGraphIdle();
            }
        }
        if(outputError!=null) throw new IllegalStateException(outputError);
        float[][] complete=output;
        if(complete==null)
            throw new IllegalStateException("Incomplete CPU blendshape/pose outputs");
        return complete;
    }
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
    @Override public void close() {
        if(graph!=null) { try { graph.closeAllPacketSources(); graph.waitUntilGraphDone(); } finally { graph.tearDown(); graph=null; } }
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
