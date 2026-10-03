package com.mirror.bench;

/** Immutable installation controls. Personal/head neutral samples are deliberately absent. */
public final class CameraControlSettings {
    public static final CameraControlSettings DEFAULT=new CameraControlSettings("","",640,480,0,false,false,0);
    public final String cameraId,fingerprint;
    public final int width,height,rotationDegrees;
    public final boolean reflectInput,mirrorInteraction;
    public final long revision;

    public CameraControlSettings(String cameraId,String fingerprint,int width,int height,int rotationDegrees,
                                 boolean reflectInput,boolean mirrorInteraction,long revision){
        validateIdentity(cameraId,"Camera ID");validateIdentity(fingerprint,"Camera fingerprint");
        if(cameraId.isEmpty()!=fingerprint.isEmpty())throw new IllegalArgumentException("Camera ID and fingerprint must both be bound or both empty");
        if(width<1||height<1||(long)width*height>16_777_216L)throw new IllegalArgumentException("Camera dimensions must be positive and at most 16M pixels");
        if(rotationDegrees!=0&&rotationDegrees!=90&&rotationDegrees!=180&&rotationDegrees!=270)
            throw new IllegalArgumentException("Camera rotation must be 0, 90, 180 or 270 clockwise degrees");
        if(revision<0)throw new IllegalArgumentException("Camera revision must be nonnegative");
        this.cameraId=cameraId;this.fingerprint=fingerprint;this.width=width;this.height=height;
        this.rotationDegrees=rotationDegrees;this.reflectInput=reflectInput;this.mirrorInteraction=mirrorInteraction;this.revision=revision;
    }
    public boolean isBound(){return !cameraId.isEmpty();}
    /** Characteristics are a compatibility fingerprint, not a USB serial-number identity. */
    public boolean matches(String id,String characteristicsFingerprint,int inputWidth,int inputHeight){
        return isBound()&&cameraId.equals(id)&&fingerprint.equals(characteristicsFingerprint)&&width==inputWidth&&height==inputHeight;
    }
    public CameraControlSettings withInput(String id,String characteristicsFingerprint,int inputWidth,int inputHeight,
                                          int clockwiseDegrees,boolean reflected,long nextRevision){
        return new CameraControlSettings(id,characteristicsFingerprint,inputWidth,inputHeight,clockwiseDegrees,reflected,mirrorInteraction,nextRevision);
    }
    public CameraControlSettings withMirror(boolean value,long nextRevision){
        return new CameraControlSettings(cameraId,fingerprint,width,height,rotationDegrees,reflectInput,value,nextRevision);
    }
    private static void validateIdentity(String value,String label){
        if(value==null||value.length()>256)throw new IllegalArgumentException(label+" must be nonnull and at most 256 characters");
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new IllegalArgumentException(label+" contains a control character");
    }
}
