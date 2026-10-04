package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Independent projection checks; no Android matrix or GL implementation participates. */
public final class AvatarFramingTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        float[] bounds={-.13f,-.02f,-.1f,.13f,.41f,.11f},pivot={0,.17f,0};
        AvatarFraming framing=new AvatarFraming(bounds,pivot,.26f,null);
        float scale=framing.scale(.625f);
        check(scale>3.2f&&scale<3.4f,"expected portrait scale");
        float[] matrix=new float[16];framing.copyFitMatrix(.625f,matrix);
        close(matrix[0],scale,"matrix scale");close(matrix[5],scale,"uniform scale y");
        close(matrix[10],scale,"uniform scale z");close(matrix[15],1,"homogeneous");
        close(matrix[13],-scale*(bounds[1]+bounds[4])*.5,"center translation");
        bounds[0]=-100;pivot[0]=100;
        close(framing.scale(.625f),scale,"input arrays defensively copied");
        check(framing.scale(1.6f)>scale,"wide output affords a larger fixed fit");
        Random random=new Random(348932);
        double max=0;
        for(int i=0;i<20000;i++) {
            double z=2*random.nextDouble()-1,az=random.nextDouble()*Math.PI*2,r=Math.sqrt(1-z*z);
            double x=.26*r*Math.cos(az),y=.17+.26*r*Math.sin(az);
            double[] transformed=transform(matrix,x,y,.26*z);
            for(double eye:new double[]{-.2,0,.2})max=Math.max(max,extent(transformed,eye,.625));
        }
        check(max<=.920001,"entire head sphere projects within margin");
        check(max>.918,"sphere fit is tight rather than arbitrarily small");
        AvatarFraming staticGeometry=new AvatarFraming(new float[]{-1,-1,-1,1,1,1},new float[3],.1f,
                new float[]{-3,-2,-1,4,5,2});
        staticGeometry.copyFitMatrix(.625f,matrix);
        for(int c=0;c<8;c++)for(double eye:new double[]{-.2,0,.2})
            check(extent(transform(matrix,(c&1)==0?-3:4,(c&2)==0?-2:5,(c&4)==0?-1:2),eye,.625)<=.920001,
                    "static AABB corner remains visible");
        AvatarFraming nearLimited=new AvatarFraming(new float[]{-1,-1,-1,1,1,1},new float[3],1,null,
                3,100,2.9f,10,0,.92f);
        check(nearLimited.scale(1)<.101f,"near plane bounds scale");
        AvatarFraming farLimited=new AvatarFraming(new float[]{-1,-1,-1,1,1,1},new float[3],1,null,
                3,100,.1f,3.2f,0,.92f);
        check(farLimited.scale(1)<.201f,"far plane bounds scale");
        invalid(()->framing.scale(0));invalid(()->framing.scale(Float.NaN));
        invalid(()->framing.copyFitMatrix(1,new float[15]));
        invalid(()->new AvatarFraming(new float[5],new float[3],1,null));
        invalid(()->new AvatarFraming(new float[]{1,0,0,-1,1,1},new float[3],1,null));
        invalid(()->new AvatarFraming(new float[6],new float[]{0,Float.NaN,0},1,null));
        invalid(()->new AvatarFraming(new float[6],new float[3],-1,null));
        invalid(()->new AvatarFraming(new float[6],new float[3],1,new float[5]));
        invalid(()->new AvatarFraming(new float[6],new float[3],1,null,3,.52f,.1f,10,.2f,1.01f));
        System.out.println("AvatarFramingTest passed: "+checks+" assertions");
        for(String path:args)actual(Path.of(path));
    }

    private static void actual(Path path)throws Exception {
        AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(path));
        int head=-1;for(int i=0;i<asset.nodes().size();i++)if(asset.nodes().get(i).name().equals("Head"))head=i;
        if(head<0)throw new AssertionError("Fixture has no Head node");
        boolean[] rotating=new boolean[asset.nodes().size()];mark(asset,head,rotating);
        FloatBuffer h=asset.nodes().get(head).worldMatrix();
        float[] pivot={h.get(12),h.get(13),h.get(14)};
        float[] bounds=emptyBounds(),staticBounds=emptyBounds();
        List<double[]> moving=new ArrayList<>(),fixed=new ArrayList<>();
        double radius=0,morphRadius=0;
        for(int n=0;n<asset.nodes().size();n++) {
            var node=asset.nodes().get(n);if(node.meshIndex()<0)continue;
            FloatBuffer world=node.worldMatrix();
            for(var primitive:asset.meshes().get(node.meshIndex()).primitives()) {
                FloatBuffer positions=primitive.positions();
                List<FloatBuffer> targets=new ArrayList<>();for(var t:primitive.morphs())targets.add(t.positions());
                for(int v=0;v<primitive.vertexCount();v++) {
                    double[] point=new double[3];
                    for(int axis=0;axis<3;axis++) {
                        point[axis]=world.get(12+axis);
                        for(int k=0;k<3;k++)point[axis]+=(double)world.get(k*4+axis)*positions.get(v*3+k);
                    }
                    include(bounds,point);
                    if(!rotating[n]){fixed.add(point);include(staticBounds,point);continue;}
                    moving.add(point);radius=Math.max(radius,distance(point,pivot));
                    // Independent [0,1] morph weights: an axis interval encloses all combinations.
                    double[] low={positions.get(v*3),positions.get(v*3+1),positions.get(v*3+2)},high=low.clone();
                    for(FloatBuffer delta:targets)if(delta!=null)for(int axis=0;axis<3;axis++) {
                        double value=delta.get(v*3+axis);low[axis]+=Math.min(0,value);high[axis]+=Math.max(0,value);
                    }
                    double sum=0;
                    for(int axis=0;axis<3;axis++) {
                        double lo=world.get(12+axis)-pivot[axis],hi=lo;
                        for(int k=0;k<3;k++) {double value=world.get(k*4+axis);
                            lo+=value>=0?value*low[k]:value*high[k];hi+=value>=0?value*high[k]:value*low[k];}
                        sum+=Math.max(lo*lo,hi*hi);
                    }
                    morphRadius=Math.max(morphRadius,Math.sqrt(sum));
                }
            }
        }
        // No rounding inward: envelope and computed scale must remain conservative.
        AvatarFraming framing=new AvatarFraming(bounds,pivot,Math.nextUp((float)Math.max(radius,morphRadius)),staticBounds);
        float[] matrix=new float[16];framing.copyFitMatrix(.625f,matrix);
        double worst=0;int poses=0;
        for(int pitch=-45;pitch<=45;pitch+=15)for(int yaw=-65;yaw<=65;yaw+=13)for(int roll=-40;roll<=40;roll+=10) {
            double[] rotation=rotation(pitch,yaw,roll);double max=0;
            for(double[] p:moving) {
                double x=p[0]-pivot[0],y=p[1]-pivot[1],z=p[2]-pivot[2];
                double[] q=transform(matrix,rotation[0]*x+rotation[3]*y+rotation[6]*z+pivot[0],
                        rotation[1]*x+rotation[4]*y+rotation[7]*z+pivot[1],rotation[2]*x+rotation[5]*y+rotation[8]*z+pivot[2]);
                for(double eye:new double[]{-.2,0,.2})max=Math.max(max,extent(q,eye,.625));
            }
            for(double[] p:fixed)for(double eye:new double[]{-.2,0,.2})max=Math.max(max,extent(transform(matrix,p[0],p[1],p[2]),eye,.625));
            if(max>.920001)throw new AssertionError("Pose clips at "+pitch+","+yaw+","+roll+": "+max);
            poses++;worst=Math.max(worst,max);
        }
        System.out.println("Actual framing: "+poses+" head combinations x 3 views; scale="+framing.scale(.625f)
                +", head radius="+radius+", morph envelope radius="+morphRadius+", worst abs NDC="+worst);
    }
    private static void mark(AvatarAsset a,int n,boolean[] marked){marked[n]=true;var children=a.nodes().get(n).children();while(children.hasRemaining())mark(a,children.get(),marked);}
    private static float[] emptyBounds(){return new float[]{Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};}
    private static void include(float[] bounds,double[] p){for(int i=0;i<3;i++){bounds[i]=Math.min(bounds[i],(float)p[i]);bounds[i+3]=Math.max(bounds[i+3],(float)p[i]);}}
    private static double distance(double[] p,float[] q){return Math.sqrt(Math.pow(p[0]-q[0],2)+Math.pow(p[1]-q[1],2)+Math.pow(p[2]-q[2],2));}
    private static double[] transform(float[] m,double x,double y,double z){return new double[]{m[0]*x+m[12],m[5]*y+m[13],m[10]*z+m[14]};}
    private static double extent(double[] p,double eye,double aspect){double depth=3-p[2];if(depth<.1-1e-6||depth>10+1e-6)throw new AssertionError("Depth clipped");return Math.max(Math.abs(p[0]-eye*p[2]/3)/(depth*.52*aspect),Math.abs(p[1])/(depth*.52));}
    private static double[] rotation(double p,double y,double r){p=Math.toRadians(p);y=Math.toRadians(y);r=Math.toRadians(r);double cx=Math.cos(p),sx=Math.sin(p),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(r),sz=Math.sin(r);return new double[]{cz*cy,sz*cy,-sy,cz*sy*sx-sz*cx,sz*sy*sx+cz*cx,cy*sx,cz*sy*cx+sz*sx,sz*sy*cx-cz*sx,cy*cx};}
    private static void invalid(Runnable work){try{work.run();throw new AssertionError("Expected invalid input rejection");}catch(IllegalArgumentException expected){checks++;}}
    private static void close(double actual,double expected,String message){check(Math.abs(actual-expected)<1e-6,message+": "+actual+" vs "+expected);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
