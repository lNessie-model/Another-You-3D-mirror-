package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * A calibration-only perspective camera for the actual Head subtree. The main scene's fixed fit
 * is preserved. One-time bounds include every mapped morph interval and all eye/jaw rotations;
 * only the already-filtered head angles change the camera, never the current expression weights.
 * Static scenery outside Head may be cropped: this window is for inspecting the face.
 */
final class CameraPreviewFraming {
    private static final float FIT_ASPECT=.625f;
    private static final double MARGIN=.92, DISTANCE=3, NEAR=.1, FAR=10;
    private final double[] headBox;
    private final float[] headBind,fit;
    private final float[] rotation=new float[16],headWorld=new float[16];
    private final double[] posedBox=new double[6];

    private CameraPreviewFraming(double[] box,float[] headBind,float[] fit){
        this.headBox=box;this.headBind=headBind;this.fit=fit;
    }

    /** Creates its own CPU rig once; never updates the rig/deformer used to render a live frame. */
    static CameraPreviewFraming fromAsset(AvatarAsset asset,String manifest)throws org.json.JSONException{
        if(asset==null||manifest==null)throw invalid("Calibration asset and manifest required");
        AvatarRig rig=new AvatarRig(asset,manifest);
        float[] fit=new float[16];AvatarGeometryBounds.fromAsset(asset,rig).copyFitMatrix(FIT_ASPECT,fit);
        return new Bounds(asset,rig).build(fit);
    }

    /** Complete column-major view/projection matrix; no allocation or additional pose worker. */
    void copyViewProjection(float[] angles,int width,int height,float[] destination){
        if(angles==null||angles.length!=3||destination==null||destination.length!=16||width<1||height<1)
            throw invalid("Head angles, viewport and sixteen camera entries required");
        for(float angle:angles)if(!Float.isFinite(angle)||Math.abs(angle)>90)throw invalid("Invalid head angle");
        AvatarRig.rotation(rotation,angles[0],angles[1],angles[2]);
        AvatarRig.multiply(headWorld,0,headBind,0,rotation,0);
        transform(posedBox,headWorld,headBox);
        for(int axis=0;axis<3;axis++){
            posedBox[axis]=fit[axis*5]*posedBox[axis]+fit[12+axis];
            posedBox[axis+3]=fit[axis*5]*posedBox[axis+3]+fit[12+axis];
        }
        double cx=(posedBox[0]+posedBox[3])*.5,cy=(posedBox[1]+posedBox[4])*.5;
        double cz=(posedBox[2]+posedBox[5])*.5;
        double halfX=(posedBox[3]-posedBox[0])*.5,halfY=(posedBox[4]-posedBox[1])*.5;
        double halfZ=(posedBox[5]-posedBox[2])*.5,depth=DISTANCE-halfZ;
        if(!(depth>NEAR&&DISTANCE+halfZ<FAR))throw invalid("Calibration geometry exceeds camera depth");
        double aspect=(double)width/height;
        // AABB support at its nearest depth covers every interior point and all eight corners.
        double tanY=Math.max(halfY,halfX/aspect)/(depth*MARGIN),tanX=tanY*aspect;
        if(!(tanY>0)||!Double.isFinite(tanY))throw invalid("Calibration geometry has no finite extent");
        Arrays.fill(destination,0);
        double cameraZ=cz+DISTANCE,a=(FAR+NEAR)/(FAR-NEAR),b=2*FAR*NEAR/(FAR-NEAR);
        destination[0]=checked(1/tanX);destination[5]=checked(1/tanY);
        destination[10]=checked(-a);destination[11]=-1;
        destination[12]=checked(-cx/tanX);destination[13]=checked(-cy/tanY);
        destination[14]=checked(a*cameraZ-b);destination[15]=checked(cameraZ);
    }

    private static final class Bounds {
        final AvatarAsset asset;final AvatarRig rig;final int head;
        final int[] parents;final float[][] local,relative;
        final double[][] lower,upper;final double[] joints,box=empty();
        Bounds(AvatarAsset asset,AvatarRig rig){
            this.asset=asset;this.rig=rig;head=rig.headNodeIndex();int count=asset.nodes().size();
            parents=new int[count];Arrays.fill(parents,-2);local=new float[count][];relative=new float[count][];
            joints=new double[count];Arrays.fill(joints,-1);
            var roots=asset.sceneRoots();while(roots.hasRemaining())visit(roots.get(),-1);
            relative[head]=identity();
            lower=new double[asset.meshes().size()][];upper=new double[lower.length][];
            // Binding curves are monotone; product correctives have endpoints 0 and gain.
            // Thus these two actual production rig evaluations bound every legal [0,1] input.
            readWeights(new float[52],lower);float[] full=new float[52];Arrays.fill(full,1);readWeights(full,upper);
            for(int m=0;m<lower.length;m++)for(int t=0;t<lower[m].length;t++){
                double a=lower[m][t],b=upper[m][t];lower[m][t]=Math.min(a,b);upper[m][t]=Math.max(a,b);
            }
        }
        void readWeights(float[] input,double[][] destination){
            rig.update(input,new float[3]);
            for(int m=0;m<destination.length;m++){
                float[] values=new float[asset.meshes().get(m).targetCount()];rig.copyMeshWeights(m,values);
                destination[m]=new double[values.length];for(int t=0;t<values.length;t++)destination[m][t]=values[t];
            }
        }
        void visit(int node,int parent){
            if(node<0||node>=parents.length||parents[node]!=-2)throw invalid("Calibration scene is not a tree");
            parents[node]=parent;local[node]=copy(asset.nodes().get(node).localMatrix());
            var children=asset.nodes().get(node).children();while(children.hasRemaining())visit(children.get(),node);
        }
        CameraPreviewFraming build(float[] fit){
            for(int n=0;n<parents.length;n++)if(belowHead(n)&&asset.nodes().get(n).meshIndex()>=0)readNode(n);
            includeJoint(rig.leftEyeNodeIndex(),false);includeJoint(rig.rightEyeNodeIndex(),false);
            includeJoint(rig.jawNodeIndex(),true);
            if(!Double.isFinite(box[0]))throw invalid("Calibration Head contains no geometry");
            double reserve=1e-6;for(double value:box)reserve=Math.max(reserve,Math.abs(value)*2e-5);
            for(int axis=0;axis<3;axis++){box[axis]-=reserve;box[axis+3]+=reserve;}
            return new CameraPreviewFraming(box,copy(asset.nodes().get(head).worldMatrix()),fit);
        }
        boolean belowHead(int node){for(int n=node;n>=0;n=parents[n])if(n==head)return true;return false;}
        int joint(int node){
            for(int n=node;n>=0&&n!=head;n=parents[n])
                if(n==rig.leftEyeNodeIndex()||n==rig.rightEyeNodeIndex()||n==rig.jawNodeIndex())return n;
            return -1;
        }
        float[] relative(int ancestor,int node){
            if(node==ancestor)return identity();
            if(parents[node]<0)throw invalid("Calibration node outside Head");
            float[] out=new float[16];AvatarRig.multiply(out,0,relative(ancestor,parents[node]),0,local[node],0);return out;
        }
        float[] toHead(int node){if(relative[node]==null)relative[node]=relative(head,node);return relative[node];}
        void readNode(int node){
            int m=asset.nodes().get(node).meshIndex(),joint=joint(node);
            float[] matrix=joint<0?toHead(node):relative(joint,node);
            double[] interval=new double[6],transformed=new double[6];
            for(var primitive:asset.meshes().get(m).primitives()){
                FloatBuffer positions=primitive.positions();FloatBuffer[] deltas=new FloatBuffer[primitive.morphs().size()];
                for(int t=0;t<deltas.length;t++)deltas[t]=primitive.morphs().get(t).positions();
                for(int v=0;v<positions.limit();v+=3){
                    for(int axis=0;axis<3;axis++){
                        double lo=positions.get(v+axis),hi=lo;
                        for(int t=0;t<deltas.length;t++)if(deltas[t]!=null){double delta=deltas[t].get(v+axis);
                            lo+=delta*(delta>=0?lower[m][t]:upper[m][t]);
                            hi+=delta*(delta>=0?upper[m][t]:lower[m][t]);
                        }
                        interval[axis]=lo;interval[axis+3]=hi;
                    }
                    transform(transformed,matrix,interval);
                    if(joint<0)include(box,transformed);else joints[joint]=Math.max(joints[joint],radius(transformed));
                }
            }
        }
        void includeJoint(int node,boolean jaw){
            if(node<0||joints[node]<0)return;
            float[] base=toHead(node);double[] translations=jaw?
                new double[]{-rig.jawLateralMeters(),0,0,rig.jawLateralMeters(),0,rig.jawForwardMeters()}:new double[6];
            double[] bound=new double[6];transform(bound,base,translations);
            double rotationNorm=1;
            if(jaw){
                float[] axis=new float[3];rig.copyJawAxis(axis);double squared=0;for(float value:axis)squared+=(double)value*value;
                // Covers the tolerated near-unit Rodrigues axis even beyond its opening endpoint.
                rotationNorm=Math.max(1,Math.max(squared,2*squared-1));
            }
            double reserve=norm(base)*joints[node]*rotationNorm;
            for(int axis=0;axis<3;axis++){bound[axis]-=reserve;bound[axis+3]+=reserve;}
            include(box,bound);
        }
    }
    private static double[] empty(){return new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};}
    private static float[] identity(){float[] matrix=new float[16];AvatarRig.identity(matrix);return matrix;}
    private static float[] copy(FloatBuffer buffer){float[] result=new float[buffer.limit()];buffer.get(result);return result;}
    private static void include(double[] out,double[] box){for(int axis=0;axis<3;axis++){out[axis]=Math.min(out[axis],box[axis]);out[axis+3]=Math.max(out[axis+3],box[axis+3]);}}
    private static void transform(double[] out,float[] matrix,double[] box){
        for(int row=0;row<3;row++){
            double lo=matrix[12+row],hi=lo;for(int col=0;col<3;col++){
                double a=matrix[col*4+row];lo+=a*(a>=0?box[col]:box[col+3]);hi+=a*(a>=0?box[col+3]:box[col]);
            }
            if(!Double.isFinite(lo)||!Double.isFinite(hi))throw invalid("Nonfinite calibration bounds");
            out[row]=lo;out[row+3]=hi;
        }
    }
    private static double radius(double[] box){double sum=0;for(int axis=0;axis<3;axis++)sum+=Math.max(box[axis]*box[axis],box[axis+3]*box[axis+3]);return Math.sqrt(sum);}
    private static double norm(float[] matrix){
        double maximum=0;for(int row=0;row<3;row++){double sum=0;for(int col=0;col<3;col++){
            double dot=0;for(int k=0;k<3;k++)dot+=(double)matrix[row*4+k]*matrix[col*4+k];sum+=Math.abs(dot);
        }maximum=Math.max(maximum,sum);}return Math.sqrt(maximum);
    }
    private static float checked(double value){if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw invalid("Nonfinite calibration camera");return (float)value;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
