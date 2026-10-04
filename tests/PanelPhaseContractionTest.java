package com.mirror.bench;

/** Reproduces the exact 239-pixel native-display RED signature without simulating a GL driver. */
public final class PanelPhaseContractionTest {
    public static void main(String[] args) {
        PanelCalibration p=new PanelCalibration(3.5f,-.125f,PanelCalibration.PitchUnits.PIXELS,.375f,
                PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP);
        if(p.viewIndex(238,1,1920,2,20)!=19)throw new AssertionError("Exact zero phase belongs to reversed view 19");
        long fusedMismatches=0,unfusedMismatches=0;String first="";
        for(int y=0;y<1920;y++)for(int x=0;x<1200;x++)for(int channel=0;channel<3;channel++) {
            float xt=(x+.5f)*3f,yt=(1920f-(y+.5f))*p.tiltSubpixelsPerPixel();
            float sum=xt+yt;sum=sum+p.physicalOffset(channel);
            float reciprocal=1f/p.pitchSubpixels();
            float fused=Math.fma(sum,reciprocal,p.phaseCycles());
            float divided=sum*reciprocal;float unfused=divided+p.phaseCycles();
            int expected=p.viewIndex(x,y,1920,channel,20);
            if(index(fused)!=expected){fusedMismatches++;if(first.isEmpty())first=x+","+y+","+channel;}
            if(index(unfused)!=expected)unfusedMismatches++;
        }
        if(fusedMismatches!=239||!first.equals("238,1,2"))throw new AssertionError("FMA RED signature changed: "+fusedMismatches+" "+first);
        if(unfusedMismatches!=0)throw new AssertionError("Uncontracted phase differs: "+unfusedMismatches);
        System.out.println("PanelPhaseContractionTest: native 1200x1920 RGB: FMA reproduces 239 RED bytes; uncontracted reference has 0 differences");
    }
    private static int index(float q){float fraction=q-(float)Math.floor(q);return 19-Math.min(19,(int)Math.floor(fraction*20f));}
}
