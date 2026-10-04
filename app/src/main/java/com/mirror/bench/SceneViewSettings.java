package com.mirror.bench;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable view controls. Distances use the renderer's normalized world, camera Z=3. */
final class SceneViewSettings {
    static final String[] BACKGROUNDS={"纯黑","深灰","蓝黑","暗红","细线网格","屏幕定位框","幽暗雾光","稀疏星点","冷蓝雾（图片）","暗红雾（图片）","古旧暗纹（图片）","紫色星尘（图片）"};
    private static final String[] KEYS={"scale","offset_x","offset_y","offset_z","rotation_x","rotation_y","rotation_z","camera_span","zero_plane"};
    static final SceneViewSettings DEFAULT=new SceneViewSettings(1,0,0,0,0,0,0,.4f,0,0);
    final float scale,offsetX,offsetY,offsetZ,rotationX,rotationY,rotationZ,cameraSpan,zeroPlane;
    final int background;
    final float expressionGain;
    final boolean mirrorMotion;
    private final float[] rotation=new float[9];
    SceneViewSettings(float scale,float x,float y,float z,float rx,float ry,float rz,float span,float zero,int background){
        this(scale,x,y,z,rx,ry,rz,span,zero,background,2.5f,true);
    }
    SceneViewSettings(float scale,float x,float y,float z,float rx,float ry,float rz,float span,float zero,int background,float gain,boolean mirror){
        require(scale,.25f,3,"缩放");require(x,-1,1,"水平位置");require(y,-1,1,"垂直位置");require(z,-1.25f,1.25f,"出入屏位置");
        require(rx,-180,180,"俯仰");require(ry,-180,180,"左右旋转");require(rz,-180,180,"画面旋转");
        require(span,0,1.2f,"相机总间距");require(zero,-1.5f,1.5f,"零视差平面");
        require(gain,.5f,4,"表情倍率");expressionGain=gain;mirrorMotion=mirror;
        if(background<0||background>=BACKGROUNDS.length)throw new IllegalArgumentException("未知背景");
        this.scale=scale;offsetX=x;offsetY=y;offsetZ=z;rotationX=rx;rotationY=ry;rotationZ=rz;cameraSpan=span;zeroPlane=zero;this.background=background;
        double cx=Math.cos(Math.toRadians(rx)),sx=Math.sin(Math.toRadians(rx));
        double cy=Math.cos(Math.toRadians(ry)),sy=Math.sin(Math.toRadians(ry));
        double cz=Math.cos(Math.toRadians(rz)),sz=Math.sin(Math.toRadians(rz));
        // Column-major Rz * Ry * Rx, applied about the fitted model center.
        rotation[0]=(float)(cz*cy);rotation[1]=(float)(sz*cy);rotation[2]=(float)-sy;
        rotation[3]=(float)(cz*sy*sx-sz*cx);rotation[4]=(float)(sz*sy*sx+cz*cx);rotation[5]=(float)(cy*sx);
        rotation[6]=(float)(cz*sy*cx+sz*sx);rotation[7]=(float)(sz*sy*cx-cz*sx);rotation[8]=(float)(cy*cx);
    }
    boolean isIdentityTransform(){return scale==1&&offsetX==0&&offsetY==0&&offsetZ==0&&rotationX==0&&rotationY==0&&rotationZ==0;}
    float eyeAt(int index,int count){
        if(count<1||count>32||index<0||index>=count)throw new IllegalArgumentException("无效视点");
        return count==1?0:(index/(float)(count-1)-.5f)*cameraSpan;
    }
    float frustumShift(float eye){if(!Float.isFinite(eye))throw new IllegalArgumentException("无效相机位置");return -eye*.1f/(3-zeroPlane);}
    void copyUserTransform(float aspect,float[] out){
        if(!Float.isFinite(aspect)||aspect<=0||out==null||out.length!=16)throw new IllegalArgumentException("无效显示比例或变换缓冲区");
        for(int i=0;i<16;i++)out[i]=0;
        for(int col=0;col<3;col++)for(int row=0;row<3;row++)out[col*4+row]=rotation[col*3+row]*scale;
        out[12]=offsetX*1.56f*aspect;out[13]=offsetY*1.56f;out[14]=offsetZ;out[15]=1;
    }
    float value(int index){return switch(index){case 0->scale;case 1->offsetX;case 2->offsetY;case 3->offsetZ;case 4->rotationX;case 5->rotationY;case 6->rotationZ;case 7->cameraSpan;case 8->zeroPlane;case 9->expressionGain;default->throw new IllegalArgumentException("未知调节项");};}
    SceneViewSettings withValue(int index,float value){
        if(index==9)return new SceneViewSettings(scale,offsetX,offsetY,offsetZ,rotationX,rotationY,rotationZ,cameraSpan,zeroPlane,background,value,mirrorMotion);
        if(index<0||index>=KEYS.length)throw new IllegalArgumentException("未知调节项");
        float[] v=new float[KEYS.length];for(int i=0;i<v.length;i++)v[i]=i==index?value:value(i);
        return new SceneViewSettings(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],background,expressionGain,mirrorMotion);
    }
    SceneViewSettings withBackground(int value){return new SceneViewSettings(scale,offsetX,offsetY,offsetZ,rotationX,rotationY,rotationZ,cameraSpan,zeroPlane,value,expressionGain,mirrorMotion);}
    SceneViewSettings withMirrorMotion(boolean value){return new SceneViewSettings(scale,offsetX,offsetY,offsetZ,rotationX,rotationY,rotationZ,cameraSpan,zeroPlane,background,expressionGain,value);}
    Map<String,Object> toMap(){
        Map<String,Object> map=new LinkedHashMap<>();map.put("schema_version",1);
        for(int i=0;i<KEYS.length;i++)map.put(KEYS[i],value(i));map.put("background",background);
        map.put("expression_gain",expressionGain);map.put("mirror_motion",mirrorMotion);return map;
    }
    static SceneViewSettings fromMap(Map<String,?> map){
        if(map.isEmpty())return DEFAULT;
        if(!(map.get("schema_version") instanceof Integer version)||version!=1)throw new IllegalArgumentException("画面配置版本不兼容");
        float[] v=new float[KEYS.length];
        for(int i=0;i<v.length;i++){if(!(map.get(KEYS[i]) instanceof Float value))throw new IllegalArgumentException("画面配置损坏："+KEYS[i]);v[i]=value;}
        if(!(map.get("background") instanceof Integer background))throw new IllegalArgumentException("背景配置损坏");
        float gain=2.5f;boolean mirror=true;
        if(map.containsKey("expression_gain")){if(!(map.get("expression_gain") instanceof Float value))throw new IllegalArgumentException("表情倍率配置损坏");gain=value;}
        if(map.containsKey("mirror_motion")){if(!(map.get("mirror_motion") instanceof Boolean value))throw new IllegalArgumentException("镜像配置损坏");mirror=value;}
        return new SceneViewSettings(v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],v[8],background,gain,mirror);
    }
    private static void require(float value,float low,float high,String label){if(!Float.isFinite(value)||value<low||value>high)throw new IllegalArgumentException(label+"超出范围");}
}
