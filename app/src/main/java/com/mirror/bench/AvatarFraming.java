package com.mirror.bench;

/**
 * Fixed, pose-independent fit for a rotating head sphere plus a static scene AABB.
 * All inputs share the unfitted scene's world coordinates. The sphere must enclose
 * every morph and child-joint pose, not just the neutral mesh. This class does not
 * guess rig semantics or enlarge the envelope while the character moves.
 *
 * Camera contract: parallel off-axis views at (eyeX, 0, distance), looking along -Z,
 * with zero-parallax plane Z=0 and |eyeX| <= maxEyeOffset. No camera roll/toe-in.
 * Fit is uniform scale after translating the supplied neutral bounds center to 0.
 */
public final class AvatarFraming {
    private final double centerX,centerY,centerZ,pivotX,pivotY,pivotZ,radius;
    private final double distance,tanY,near,far,maxEye,margin;
    private final float[] staticBounds;

    /** Matches the avatar renderer's current camera: distance 3, tan(FOVy/2) .52, eye range +/-.2. */
    public AvatarFraming(float[] neutralBounds,float[] headPivot,float headRadius,float[] staticBounds) {
        this(neutralBounds,headPivot,headRadius,staticBounds,3,.52f,.1f,10,.2f,.92f);
    }

    public AvatarFraming(float[] neutralBounds,float[] headPivot,float headRadius,float[] staticBounds,
                         float cameraDistance,float tanHalfVerticalFov,float nearPlane,float farPlane,
                         float maxEyeOffset,float ndcMargin) {
        validateBounds(neutralBounds);
        if(headPivot==null||headPivot.length!=3)throw invalid("headPivot requires three coordinates");
        for(float v:headPivot)if(!Float.isFinite(v))throw invalid("headPivot must be finite");
        if(!Float.isFinite(headRadius)||headRadius<0)throw invalid("Head radius must be finite and nonnegative");
        if(!(cameraDistance>0)||!Float.isFinite(cameraDistance)||!(tanHalfVerticalFov>0)||!Float.isFinite(tanHalfVerticalFov)
                ||!(nearPlane>0)||!(nearPlane<cameraDistance)||!Float.isFinite(nearPlane)
                ||!(farPlane>cameraDistance)||!Float.isFinite(farPlane)
                ||maxEyeOffset<0||!Float.isFinite(maxEyeOffset)||!(ndcMargin>0)||ndcMargin>1||!Float.isFinite(ndcMargin))
            throw invalid("Invalid off-axis camera or margin");
        if(staticBounds!=null)validateBounds(staticBounds);
        this.staticBounds=staticBounds==null?null:staticBounds.clone();
        centerX=((double)neutralBounds[0]+neutralBounds[3])*.5;
        centerY=((double)neutralBounds[1]+neutralBounds[4])*.5;
        centerZ=((double)neutralBounds[2]+neutralBounds[5])*.5;
        pivotX=headPivot[0]-centerX;pivotY=headPivot[1]-centerY;pivotZ=headPivot[2]-centerZ;
        radius=headRadius;distance=cameraDistance;tanY=tanHalfVerticalFov;
        near=nearPlane;far=farPlane;maxEye=maxEyeOffset;margin=ndcMargin;
    }

    /** Aspect is final display width / height, independent of the offscreen rendering resolution. */
    public float scale(float aspect) {
        if(!(aspect>0)||!Float.isFinite(aspect))throw invalid("Display aspect must be finite and positive");
        double tanX=tanY*aspect;
        double limit=Double.POSITIVE_INFINITY;
        for(int side=-1;side<=1;side+=2) {
            // ndcX=(X-eyeX*Z/distance)/((distance-Z)*tanX).
            // Each clip plane becomes s*(side*qX+(margin*tanX-side*eyeX/distance)*qZ) <= distance*margin*tanX.
            // Support is affine in eyeX, so both extreme eyes also cover every intermediate view.
            for(int eye=-1;eye<=1;eye+=2)
                limit=Math.min(limit,limit(side,0,margin*tanX-side*eye*maxEye/distance,distance*margin*tanX));
            limit=Math.min(limit,limit(0,side,margin*tanY,distance*margin*tanY));
        }
        limit=Math.min(limit,limit(0,0,1,distance-near));
        limit=Math.min(limit,limit(0,0,-1,far-distance));
        if(!(limit>0)||!Double.isFinite(limit)||limit>Float.MAX_VALUE)throw invalid("Envelope has no representable finite fit");
        // Round toward the interior and reserve a few float matrix/vertex arithmetic ulps.
        float result=Math.nextDown((float)(limit*(1-1e-6)));
        if(!(result>0)||!Float.isFinite(result))throw invalid("Envelope fit underflows");
        return result;
    }

    /** Column-major T/S result: fitted point = scale * (worldPoint - neutralBoundsCenter). */
    public void copyFitMatrix(float aspect,float[] destination) {
        if(destination==null||destination.length!=16)throw invalid("Fit matrix requires sixteen entries");
        float s=scale(aspect),x=(float)(-s*centerX),y=(float)(-s*centerY),z=(float)(-s*centerZ);
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw invalid("Fit translation overflow");
        for(int i=0;i<16;i++)destination[i]=0;
        destination[0]=destination[5]=destination[10]=s;destination[15]=1;
        destination[12]=x;destination[13]=y;destination[14]=z;
    }

    private double limit(double nx,double ny,double nz,double planeDistance) {
        double support=nx*pivotX+ny*pivotY+nz*pivotZ+radius*Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(staticBounds!=null) {
            double box=nx*((nx>=0?staticBounds[3]:staticBounds[0])-centerX)
                    +ny*((ny>=0?staticBounds[4]:staticBounds[1])-centerY)
                    +nz*((nz>=0?staticBounds[5]:staticBounds[2])-centerZ);
            support=Math.max(support,box);
        }
        return support<=0?Double.POSITIVE_INFINITY:planeDistance/support;
    }
    private static void validateBounds(float[] b) {
        if(b==null||b.length!=6)throw invalid("Bounds require minXYZ/maxXYZ");
        for(float v:b)if(!Float.isFinite(v))throw invalid("Bounds must be finite");
        for(int i=0;i<3;i++)if(b[i]>b[i+3])throw invalid("Bounds min exceeds max");
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
}
