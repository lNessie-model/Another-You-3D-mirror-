package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.ByteOrder;

/**
 * CPU-only bounds of successfully uploaded XYZ/normalXYZ vertices. Single GL-owner caller.
 * Call updatePrimitive only after that exact revision's VBO upload succeeds, before releasing its
 * pose lease. No buffer or requested pose is retained. World matrices must describe the same
 * displayed revision. Unknown/invalid data returns false and writes the full [0,0,1,1] rectangle.
 */
final class AvatarScreenBounds {
    private static final int STRIDE=6;
    // Conservatively budget float32 dot/matrix rounding, including possible subnormal underflow.
    private static final double ROUNDING=16d*Math.ulp(1f)/2,UNDERFLOW=32d*Float.MIN_VALUE;
    private final Bound[][] bounds;
    private final int[] nodeMeshes;
    private final double[] fitted=new double[16],fittedErrors=new double[16];
    private final double[] corners=new double[32],cornerErrors=new double[32];
    private final double[] clip=new double[4],clipErrors=new double[4];
    private boolean invalidIdentity;

    private static final class Bound {
        final int vertices;boolean valid;
        double minX,minY,minZ,maxX,maxY,maxZ;
        Bound(int vertices){this.vertices=vertices;}
    }
    AvatarScreenBounds(AvatarAsset asset,AvatarRig rig){
        if(asset==null||rig==null||rig.asset()!=asset)throw new IllegalArgumentException("Exact asset/rig required");
        bounds=new Bound[asset.meshes().size()][];
        for(int m=0;m<bounds.length;m++){
            var primitives=asset.meshes().get(m).primitives();bounds[m]=new Bound[primitives.size()];
            for(int p=0;p<bounds[m].length;p++)bounds[m][p]=new Bound(primitives.get(p).vertexCount());
        }
        nodeMeshes=new int[asset.nodes().size()];
        for(int n=0;n<nodeMeshes.length;n++){
            int mesh=asset.nodes().get(n).meshIndex();
            if(mesh>=bounds.length)throw new IllegalArgumentException("Node mesh outside exact asset");
            nodeMeshes[n]=rig.activeNode(n)?mesh:-1;
        }
    }

    /** The readable span is exactly vertexCount*6, including normals; position/limit/mark stay intact. */
    void updatePrimitive(int mesh,int primitive,FloatBuffer uploaded){
        if(mesh<0||mesh>=bounds.length||primitive<0||primitive>=bounds[mesh].length){
            // An unknown upload identity cannot be repaired by guessing which old cache it replaced.
            invalidIdentity=true;return;
        }
        Bound bound=bounds[mesh][primitive];bound.valid=false;
        if(uploaded==null||uploaded.order()!=ByteOrder.nativeOrder()||bound.vertices<1||bound.vertices>Integer.MAX_VALUE/STRIDE
                ||uploaded.remaining()!=bound.vertices*STRIDE)return;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        int first=uploaded.position();
        for(int v=0;v<bound.vertices;v++){
            int index=first+v*STRIDE;
            for(int a=0;a<STRIDE;a++)if(!Float.isFinite(uploaded.get(index+a)))return;
            double x=uploaded.get(index),y=uploaded.get(index+1),z=uploaded.get(index+2);
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        bound.minX=minX;bound.minY=minY;bound.minZ=minZ;
        bound.maxX=maxX;bound.maxY=maxY;bound.maxZ=maxZ;bound.valid=true;
    }

    /** Union of all active draw instances and independent views. Steady-state work allocates nothing. */
    boolean copyUvBounds(float[] worlds,float[] fit,float[] views,int count,int viewWidth,int viewHeight,
                         int outputWidth,int outputHeight,float[] destination){
        if(destination==null||destination.length<4)throw new IllegalArgumentException("Four UV entries required");
        destination[0]=destination[1]=0;destination[2]=destination[3]=1;
        if(invalidIdentity||worlds==null||worlds.length/16<nodeMeshes.length||fit==null||fit.length<16
                ||views==null||count<1||count>views.length/16||viewWidth<1||viewHeight<1
                ||outputWidth<1||outputHeight<1||!finiteMatrix(fit,0))return false;
        for(int view=0;view<count;view++)if(!finiteMatrix(views,view*16))return false;
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        boolean geometry=false;
        for(int node=0;node<nodeMeshes.length;node++){
            int mesh=nodeMeshes[node];if(mesh<0)continue;
            if(!finiteMatrix(worlds,node*16)||!fitWorld(fit,worlds,node*16))return false;
            for(int primitive=0;primitive<bounds[mesh].length;primitive++){
                Bound bound=bounds[mesh][primitive];if(!bound.valid||!transformCorners(bound))return false;
                geometry=true;
                for(int view=0;view<count;view++)for(int corner=0;corner<8;corner++){
                    if(!projectCorner(views,view*16,corner*4))return false;
                    double lowW=clip[3]-clipErrors[3],highW=clip[3]+clipErrors[3];
                    double epsilon=1e-7*Math.max(1,Math.max(Math.abs(clip[2]),Math.abs(clip[3])));
                    // Entire local AABB must be on the safe side of W=0 and the near clip plane.
                    if(!(lowW>epsilon)||!(clip[2]+clip[3]>clipErrors[2]+clipErrors[3]+epsilon))return false;
                    double loX=clip[0]-clipErrors[0],hiX=clip[0]+clipErrors[0];
                    double loY=clip[1]-clipErrors[1],hiY=clip[1]+clipErrors[1];
                    minX=Math.min(minX,Math.min(loX/lowW,loX/highW));
                    maxX=Math.max(maxX,Math.max(hiX/lowW,hiX/highW));
                    minY=Math.min(minY,Math.min(loY/lowW,loY/highW));
                    maxY=Math.max(maxY,Math.max(hiY/lowW,hiY/highW));
                }
            }
        }
        if(!geometry||!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(maxX)||!Double.isFinite(maxY))return false;
        double padX=2d/viewWidth+2d/outputWidth,padY=2d/viewHeight+2d/outputHeight;
        destination[0]=lower(minX*.5+.5-padX);destination[1]=lower(minY*.5+.5-padY);
        destination[2]=upper(maxX*.5+.5+padX);destination[3]=upper(maxY*.5+.5+padY);
        return true;
    }

    private boolean fitWorld(float[] fit,float[] worlds,int offset){
        for(int col=0;col<4;col++)for(int row=0;row<4;row++){
            double value=0,magnitude=0;
            for(int k=0;k<4;k++){
                double product=(double)fit[k*4+row]*worlds[offset+col*4+k];value+=product;magnitude+=Math.abs(product);
            }
            if(!representable(value,magnitude))return false;
            fitted[col*4+row]=value;fittedErrors[col*4+row]=ROUNDING*magnitude+UNDERFLOW;
        }
        return true;
    }
    private boolean transformCorners(Bound bound){
        for(int c=0;c<8;c++){
            double x=(c&1)==0?bound.minX:bound.maxX,y=(c&2)==0?bound.minY:bound.maxY,z=(c&4)==0?bound.minZ:bound.maxZ;
            for(int row=0;row<4;row++){
                double a=fitted[row]*x,b=fitted[4+row]*y,d=fitted[8+row]*z,e=fitted[12+row];
                double value=a+b+d+e,magnitude=Math.abs(a)+Math.abs(b)+Math.abs(d)+Math.abs(e);
                if(!representable(value,magnitude))return false;
                corners[c*4+row]=value;
                cornerErrors[c*4+row]=Math.abs(x)*fittedErrors[row]+Math.abs(y)*fittedErrors[4+row]
                    +Math.abs(z)*fittedErrors[8+row]+fittedErrors[12+row]+ROUNDING*magnitude+UNDERFLOW;
            }
        }
        return true;
    }
    private boolean projectCorner(float[] views,int view,int corner){
        for(int row=0;row<4;row++){
            double value=0,magnitude=0,error=0;
            for(int k=0;k<4;k++){
                double matrix=views[view+k*4+row],product=matrix*corners[corner+k];
                value+=product;magnitude+=Math.abs(product);error+=Math.abs(matrix)*cornerErrors[corner+k];
            }
            if(!representable(value,magnitude)||!Double.isFinite(error))return false;
            clip[row]=value;clipErrors[row]=error+ROUNDING*magnitude+UNDERFLOW;
        }
        return true;
    }
    private static boolean finiteMatrix(float[] matrix,int offset){for(int i=0;i<16;i++)if(!Float.isFinite(matrix[offset+i]))return false;return true;}
    private static boolean representable(double value,double magnitude){return Double.isFinite(value)&&Double.isFinite(magnitude)&&magnitude<=Float.MAX_VALUE;}
    private static float lower(double value){return (float)Math.max(0,Math.min(1,Math.nextDown((float)value)));}
    private static float upper(double value){return (float)Math.max(0,Math.min(1,Math.nextUp((float)value)));}
}
