package com.mirror.bench;
import com.google.mediapipe.proto.CalculatorOptionsProto.CalculatorOptions;
import com.google.mediapipe.tasks.vision.facegeometry.proto.FaceGeometryGraphOptionsProto.FaceGeometryGraphOptions;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
public final class FaceGeometryPostGraphTest {
    private static int checks;
    private static void check(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        for(boolean smoothing:new boolean[]{false,true}){
            var graph=FaceGeometryPostGraph.configuration("/private/metadata.binarypb",smoothing);
            check(graph.getNumThreads()==2,"original graph thread count");
            check(graph.getInputStreamList().equals(List.of("coords","size")),"same input streams");
            check(graph.getOutputStreamList().equals(List.of("geometry","landmarks")),"no fabricated expression output");
            check(graph.getNodeCount()==5,"only CPU52 node removed");
            String[] expected={"TensorConverterCalculator","TensorsToLandmarksCalculator","LandmarksSmoothingCalculator","ConcatenateNormalizedLandmarkListVectorCalculator","mediapipe.tasks.vision.face_geometry.FaceGeometryFromLandmarksGraph"};
            for(int i=0;i<5;i++)check(graph.getNode(i).getCalculator().equals(expected[i]),"actual graph node order");
            check(graph.getNode(0).getInputStreamList().equals(List.of("MATRIX:coords"))&&graph.getNode(0).getOutputStreamList().equals(List.of("TENSORS:tensors")),"matrix converter wiring");
            check(graph.getNode(1).getInputStreamList().equals(List.of("TENSORS:tensors"))&&graph.getNode(1).getOutputStreamList().equals(List.of("NORM_LANDMARKS:raw_landmarks")),"raw478 wiring");
            check(graph.getNode(2).getInputStreamList().equals(List.of("NORM_LANDMARKS:raw_landmarks","IMAGE_SIZE:size"))&&graph.getNode(2).getOutputStreamList().equals(List.of("NORM_FILTERED_LANDMARKS:landmarks")),"same smoothing feed");
            check(graph.getNode(4).getInputStreamList().equals(List.of("FACE_LANDMARKS:landmark_vector","IMAGE_SIZE:size")),"same pose feed");
            var options=graph.getNode(4).getOptions().getExtension(FaceGeometryGraphOptions.ext);
            check(options.getGeometryPipelineOptions().getMetadataFile().getFileName().equals("/private/metadata.binarypb"),"canonical metadata retained");
            Method originalLandmark=FacePostGraph.class.getDeclaredMethod("landmarkOptions");originalLandmark.setAccessible(true);
            Method originalSmooth=FacePostGraph.class.getDeclaredMethod("smoothingOptions",boolean.class);originalSmooth.setAccessible(true);
            check(Arrays.equals(graph.getNode(1).getOptions().toByteArray(),((CalculatorOptions)originalLandmark.invoke(null)).toByteArray()),"actual original 478 options byte identical");
            check(Arrays.equals(graph.getNode(2).getOptions().toByteArray(),((CalculatorOptions)originalSmooth.invoke(null,smoothing)).toByteArray()),"actual original OneEuro/no-filter options byte identical");
        }
        System.out.println("FaceGeometryPostGraphTest: "+checks+" checks passed; real protobuf configuration, no native graph/ADB");
    }
}
