package com.mirror.bench;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** RGBA8 comparison for the diagnostic clear color (.035,.045,.06,1); no GL dependencies. */
public final class AvatarPixelComparison {
    private AvatarPixelComparison(){}
    public static Stats compare(ByteBuffer serial,ByteBuffer candidate,int width,int height) {
        long required=(long)width*height*4;
        if(serial==null||candidate==null||width<=0||height<=0||required>Integer.MAX_VALUE
                ||serial.capacity()<required||candidate.capacity()<required)throw new IllegalArgumentException("Invalid RGBA dimensions/buffer capacity");
        ByteBuffer a=serial.asReadOnlyBuffer(),b=candidate.asReadOnlyBuffer();a.clear();b.clear();a.limit((int)required);b.limit((int)required);
        long mismatches=0,squared=0,alpha=0;int max=0,af=0,bf=0,ao=0,bo=0;
        for(int p=0;p<required;p+=4) {
            int ar=a.get(p)&255,ag=a.get(p+1)&255,ab=a.get(p+2)&255;
            int br=b.get(p)&255,bg=b.get(p+1)&255,bb=b.get(p+2)&255;
            if(Math.abs(ar-9)>2||Math.abs(ag-11)>2||Math.abs(ab-15)>2)af++;
            if(Math.abs(br-9)>2||Math.abs(bg-11)>2||Math.abs(bb-15)>2)bf++;
            if((a.get(p+3)&255)!=255)ao++;if((b.get(p+3)&255)!=255)bo++;
            if(a.get(p+3)!=b.get(p+3))alpha++;
            for(int c=0;c<3;c++){int delta=Math.abs((a.get(p+c)&255)-(b.get(p+c)&255));if(delta!=0)mismatches++;squared+=(long)delta*delta;max=Math.max(max,delta);}
        }
        return new Stats(width*height,mismatches,max,Math.sqrt(squared/(required*.75)),alpha,af,bf,ao,bo,hash(a),hash(b));
    }
    private static String hash(ByteBuffer pixels) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(pixels);byte[] bytes=digest.digest();
            char[] out=new char[bytes.length*2],hex="0123456789abcdef".toCharArray();for(int i=0;i<bytes.length;i++){out[i*2]=hex[(bytes[i]&255)>>>4];out[i*2+1]=hex[bytes[i]&15];}
            return new String(out);
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static final class Stats {
        public final int pixels,maxRgbError,serialForeground,candidateForeground,serialNonOpaque,candidateNonOpaque;
        public final long rgbMismatches,alphaMismatches;public final double rmse;
        public final String serialSha256,candidateSha256;
        private Stats(int pixels,long rgb,int max,double rmse,long alpha,int af,int bf,int ao,int bo,String ah,String bh){
            this.pixels=pixels;rgbMismatches=rgb;maxRgbError=max;this.rmse=rmse;alphaMismatches=alpha;
            serialForeground=af;candidateForeground=bf;serialNonOpaque=ao;candidateNonOpaque=bo;serialSha256=ah;candidateSha256=bh;
        }
        public boolean passed(){return maxRgbError<=1&&rmse<=.1&&alphaMismatches==0&&serialNonOpaque==0&&candidateNonOpaque==0
                &&serialForeground>=Math.max(1,pixels/1000)&&candidateForeground>=Math.max(1,pixels/1000);}
    }
}
