package com.mirror.bench;

/** Camera pixels -> upright inference pixels: clockwise rotation, then optional horizontal reflection.
 * Pixel coordinates use an integer top-left origin. This does not transform avatar actions or panel views.
 * Immutable geometry; callers own and reuse separate ARGB buffers. No allocation occurs in applyArgb.
 */
final class CameraInputTransform {
    private final int width,height,rotation,outputWidth,outputHeight,pixels;
    private final boolean reflect;
    CameraInputTransform(int width,int height,int clockwiseDegrees,boolean correctHorizontalReflection) {
        if(width<1||height<1||(long)width*height>16_777_216L)
            throw new IllegalArgumentException("Camera transform needs positive dimensions and at most 16M pixels");
        if(clockwiseDegrees!=0&&clockwiseDegrees!=90&&clockwiseDegrees!=180&&clockwiseDegrees!=270)
            throw new IllegalArgumentException("Camera rotation must be 0, 90, 180 or 270 clockwise degrees");
        this.width=width;this.height=height;rotation=clockwiseDegrees;reflect=correctHorizontalReflection;
        boolean swap=rotation==90||rotation==270;
        outputWidth=swap?height:width;outputHeight=swap?width:height;pixels=width*height;
    }
    int sourceWidth(){return width;}
    int sourceHeight(){return height;}
    int outputWidth(){return outputWidth;}
    int outputHeight(){return outputHeight;}
    int clockwiseDegrees(){return rotation;}
    boolean correctHorizontalReflection(){return reflect;}
    boolean isIdentity(){return rotation==0&&!reflect;}

    /** Linear row-major target index of one original camera pixel. */
    int outputIndexForSource(int x,int y) {
        if(x<0||x>=width||y<0||y>=height)throw new IllegalArgumentException("Source coordinate outside frame");
        int targetX,targetY;
        switch(rotation) {
            case 90 -> {targetX=height-1-y;targetY=x;}
            case 180 -> {targetX=width-1-x;targetY=height-1-y;}
            case 270 -> {targetX=y;targetY=width-1-x;}
            default -> {targetX=x;targetY=y;}
        }
        if(reflect)targetX=outputWidth-1-targetX;
        return targetY*outputWidth+targetX;
    }
    /** Linear original index to read for a normalized inference pixel. */
    int sourceIndexForOutput(int x,int y) {
        if(x<0||x>=outputWidth||y<0||y>=outputHeight)throw new IllegalArgumentException("Output coordinate outside frame");
        return sourceIndexUnchecked(x,y);
    }
    private int sourceIndexUnchecked(int x,int y) {
        int unreflectedX=reflect?outputWidth-1-x:x;
        return switch(rotation) {
            case 90 -> (height-1-unreflectedX)*width+y;
            case 180 -> (height-1-y)*width+(width-1-unreflectedX);
            case 270 -> unreflectedX*width+(width-1-y);
            default -> y*width+unreflectedX;
        };
    }
    /** Copies exactly sourceWidth*sourceHeight complete ARGB integers; extra buffer capacity is untouched.
     * Reusing the same array is allowed only for identity. This is a reference CPU operation, not RGA.
     */
    void applyArgb(int[] source,int[] output) {
        if(source==null||output==null||source.length<pixels||output.length<pixels)
            throw new IllegalArgumentException("ARGB buffers are missing or smaller than the frame");
        if(source==output&&!isIdentity())throw new IllegalArgumentException("Nonidentity transform needs distinct buffers");
        if(isIdentity()){System.arraycopy(source,0,output,0,pixels);return;}
        int index=0;
        for(int y=0;y<outputHeight;y++)for(int x=0;x<outputWidth;x++)
            output[index++]=source[sourceIndexUnchecked(x,y)];
    }
}
