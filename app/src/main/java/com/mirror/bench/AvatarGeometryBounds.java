package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * One-time, continuous geometry envelope for AvatarRig. No GL, sampling, or live rig mutation.
 * Morph intervals include [0,1] and each node/mesh default, including unbound defaults outside [0,1].
 * Every rigid control is enclosed for all rotations, stronger than the configured angular limits.
 * Nonuniform static hierarchies are supported with a conservative induced linear norm bound.
 */
public final class AvatarGeometryBounds {
    private static final double NUMERIC_RESERVE=2e-5;
    private AvatarGeometryBounds(){}

    /**
     * Returns a fixed fit in the asset's existing world coordinates. Pass final display aspect to
     * copyFitMatrix. A uniform rootScale is a coordinate-unit conversion absorbed by auto-framing:
     * do not multiply its result by rootScale or another zoom greater than one.
     * Camera defaults are AvatarFraming's documented off-axis camera, not an arbitrary projection.
     */
    public static AvatarFraming fromAsset(AvatarAsset asset,AvatarRig rig) {
        if(asset==null||rig==null||rig.asset()!=asset)throw invalid("Bounds require the rig's exact source asset");
        return new Builder(asset,rig).build();
    }

    private static final class Builder {
        final AvatarAsset asset;final AvatarRig rig;final int[] parents;
        final int head,jaw,leftEye,rightEye;
        final double[][] local,bindWorld,headRelative;
        final double[] neutral=empty(),statics=empty();
        final double[] jointRadii;
        double headRadius;boolean geometry;
        Builder(AvatarAsset asset,AvatarRig rig) {
            this.asset=asset;this.rig=rig;head=rig.headNodeIndex();jaw=rig.jawNodeIndex();
            leftEye=rig.leftEyeNodeIndex();rightEye=rig.rightEyeNodeIndex();
            int count=asset.nodes().size();parents=new int[count];Arrays.fill(parents,-2);
            local=new double[count][];bindWorld=new double[count][];headRelative=new double[count][];
            jointRadii=new double[count];Arrays.fill(jointRadii,-1);
            var roots=asset.sceneRoots();while(roots.hasRemaining())visit(roots.get(),-1);
            headRelative[head]=identity();
        }
        private void visit(int index,int parent) {
            if(index<0||index>=parents.length||parents[index]!=-2)throw invalid("Bounds scene is not a tree");
            parents[index]=parent;var node=asset.nodes().get(index);
            local[index]=copy(node.localMatrix());bindWorld[index]=copy(node.worldMatrix());
            var children=node.children();while(children.hasRemaining())visit(children.get(),index);
        }
        AvatarFraming build() {
            for(int n=0;n<parents.length;n++)if(parents[n]!=-2&&asset.nodes().get(n).meshIndex()>=0)readNode(n);
            if(!geometry)throw invalid("Cannot frame a scene without vertices");
            includeJoint(leftEye,false);includeJoint(rightEye,false);includeJoint(jaw,true);
            double[] headWorld=bindWorld[head];
            double worldRadius=norm(headWorld)*headRadius;
            double coordinateSize=Math.max(worldRadius,Math.max(Math.abs(headWorld[12]),Math.max(Math.abs(headWorld[13]),Math.abs(headWorld[14]))));
            worldRadius+=NUMERIC_RESERVE*Math.max(coordinateSize,1e-4);
            finite(worldRadius,"head world radius");
            float[] pivot={checked(headWorld[12]),checked(headWorld[13]),checked(headWorld[14])};
            float radius=upper(worldRadius);
            float[] staticBox=null;
            if(Double.isFinite(statics[0])) {
                double max=1e-4;for(double v:statics)max=Math.max(max,Math.abs(v));
                for(int axis=0;axis<3;axis++){statics[axis]-=max*NUMERIC_RESERVE;statics[axis+3]+=max*NUMERIC_RESERVE;}
                staticBox=boxFloats(statics);
            }
            return new AvatarFraming(boxFloats(neutral),pivot,radius,staticBox);
        }
        private void readNode(int nodeIndex) {
            var node=asset.nodes().get(nodeIndex);var mesh=asset.meshes().get(node.meshIndex());
            FloatBuffer initial=node.weights().limit()==0?mesh.weights():node.weights();
            double[] defaults=new double[initial.limit()];for(int i=0;i<defaults.length;i++)defaults[i]=initial.get(i);
            boolean inHead=descendant(nodeIndex,head);
            int joint=inHead?jointAncestor(nodeIndex):-1;
            double[] targetTransform=!inHead?bindWorld[nodeIndex]:joint>=0?relative(joint,nodeIndex):relativeToHead(nodeIndex);
            for(var primitive:mesh.primitives()) {
                FloatBuffer positions=primitive.positions();FloatBuffer[] deltas=new FloatBuffer[primitive.morphs().size()];
                for(int i=0;i<deltas.length;i++)deltas[i]=primitive.morphs().get(i).positions();
                double[] interval=new double[6],rest=new double[3],transformed=new double[6];
                for(int vertex=0;vertex<primitive.vertexCount();vertex++) {
                    for(int axis=0;axis<3;axis++) {
                        double p=positions.get(vertex*3+axis),lo=p,hi=p,bind=p;
                        for(int t=0;t<deltas.length;t++)if(deltas[t]!=null) {
                            double delta=deltas[t].get(vertex*3+axis),minWeight=Math.min(0,defaults[t]),maxWeight=Math.max(1,defaults[t]);
                            lo+=delta>=0?delta*minWeight:delta*maxWeight;
                            hi+=delta>=0?delta*maxWeight:delta*minWeight;bind+=delta*defaults[t];
                        }
                        finite(lo,"morph lower bound");finite(hi,"morph upper bound");finite(bind,"default morph position");
                        interval[axis]=lo;interval[axis+3]=hi;rest[axis]=bind;
                    }
                    includePoint(neutral,bindWorld[nodeIndex],rest);
                    transformBox(transformed,targetTransform,interval);geometry=true;
                    if(!inHead)includeBox(statics,transformed);
                    else if(joint>=0)jointRadii[joint]=Math.max(jointRadii[joint],radius(transformed));
                    else headRadius=Math.max(headRadius,radius(transformed));
                }
            }
        }
        private void includeJoint(int joint,boolean isJaw) {
            if(joint<0||jointRadii[joint]<0)return;
            double[] base=relativeToHead(joint);
            double[] translations=isJaw?new double[]{-rig.jawLateralMeters(),0,0,rig.jawLateralMeters(),0,rig.jawForwardMeters()}:new double[6];
            double[] transformed=new double[6];transformBox(transformed,base,translations);
            double moving=norm(base)*jointRadii[joint]*(isJaw?jawRotationNorm(rig):1);
            // Minkowski sum of translated pivot interval and transformed sphere; triangle inequality.
            double total=radius(transformed)+moving;finite(total,"rigid joint envelope");
            headRadius=Math.max(headRadius,total);
        }
        private boolean descendant(int node,int ancestor){for(int n=node;n>=0;n=parents[n])if(n==ancestor)return true;return false;}
        private int jointAncestor(int node) {
            for(int n=node;n>=0&&n!=head;n=parents[n])if(n==jaw||n==leftEye||n==rightEye)return n;
            return -1;
        }
        private double[] relativeToHead(int node) {
            if(headRelative[node]!=null)return headRelative[node];
            if(parents[node]<0)throw invalid("Node is not below Head");
            return headRelative[node]=multiply(relativeToHead(parents[node]),local[node]);
        }
        private double[] relative(int ancestor,int node) {
            if(node==ancestor)return identity();
            if(parents[node]<0)throw invalid("Node is not below rigid control");
            return multiply(relative(ancestor,parents[node]),local[node]);
        }
    }

    private static double jawRotationNorm(AvatarRig rig) {
        float[] axis=new float[3];rig.copyJawAxis(axis);
        double squared=(double)axis[0]*axis[0]+(double)axis[1]*axis[1]+(double)axis[2]*axis[2];
        if(squared<=1)return 1;
        // AvatarRig tolerates a near-unit jaw axis and uses it directly in Rodrigues' formula.
        // Its parallel eigenvalue is cos(a)+(1-cos(a))*|axis|²; perpendicular norm² is cos²(a)+sin²(a)*|axis|².
        double radians=Math.toRadians(rig.jawOpenDegrees()),s=Math.sin(radians),c=Math.cos(radians);
        return Math.max(1+(1-c)*(squared-1),Math.sqrt(1+s*s*(squared-1)));
    }
    /** ||A||2 <= sqrt(||A^T A||infinity); exact for uniform scale/rotation, safe for shear/nonuniform chains. */
    private static double norm(double[] matrix) {
        double max=0;
        for(int row=0;row<3;row++) {
            double sum=0;for(int col=0;col<3;col++) {
                double dot=0;for(int k=0;k<3;k++)dot+=matrix[row*4+k]*matrix[col*4+k];sum+=Math.abs(dot);
            }
            max=Math.max(max,sum);
        }
        finite(max,"hierarchy operator norm");return Math.sqrt(max);
    }
    private static double radius(double[] box) {
        double sum=0;for(int axis=0;axis<3;axis++)sum+=Math.max(box[axis]*box[axis],box[axis+3]*box[axis+3]);
        finite(sum,"geometry radius");return Math.sqrt(sum);
    }
    private static void transformBox(double[] out,double[] matrix,double[] box) {
        for(int row=0;row<3;row++) {
            double lo=matrix[12+row],hi=lo;
            for(int col=0;col<3;col++){double a=matrix[col*4+row];lo+=a>=0?a*box[col]:a*box[col+3];hi+=a>=0?a*box[col+3]:a*box[col];}
            finite(lo,"transformed lower bound");finite(hi,"transformed upper bound");out[row]=lo;out[row+3]=hi;
        }
    }
    private static void includePoint(double[] out,double[] matrix,double[] point) {
        for(int row=0;row<3;row++) {
            double value=matrix[12+row];for(int col=0;col<3;col++)value+=matrix[col*4+row]*point[col];
            finite(value,"neutral world bound");out[row]=Math.min(out[row],value);out[row+3]=Math.max(out[row+3],value);
        }
    }
    private static void includeBox(double[] out,double[] box){for(int i=0;i<3;i++){out[i]=Math.min(out[i],box[i]);out[i+3]=Math.max(out[i+3],box[i+3]);}}
    private static double[] empty(){return new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};}
    private static double[] copy(FloatBuffer buffer){double[] out=new double[buffer.limit()];for(int i=0;i<out.length;i++){out[i]=buffer.get(i);finite(out[i],"node matrix");}return out;}
    private static double[] identity(){return new double[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static double[] multiply(double[] a,double[] b) {
        double[] out=new double[16];for(int col=0;col<4;col++)for(int row=0;row<4;row++) {
            double v=0;for(int k=0;k<4;k++)v+=a[k*4+row]*b[col*4+k];finite(v,"relative node transform");out[col*4+row]=v;
        }return out;
    }
    private static float[] boxFloats(double[] box){float[] out=new float[6];for(int i=0;i<3;i++){out[i]=Math.nextDown(checked(box[i]));out[i+3]=upper(box[i+3]);if(!Float.isFinite(out[i]))throw invalid("Bounds exceed float range");}return out;}
    private static float upper(double value){float out=Math.nextUp(checked(value));if(!Float.isFinite(out))throw invalid("Bounds exceed float range");return out;}
    private static float checked(double value){if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw invalid("Bounds exceed finite float range");return (float)value;}
    private static void finite(double value,String field){if(!Double.isFinite(value))throw invalid("Nonfinite "+field);}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
