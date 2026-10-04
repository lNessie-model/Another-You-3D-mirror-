package com.mirror.bench;
import java.util.Arrays;
import java.util.HashSet;
public final class PanelTestImagesTest {
    public static void main(String[] args) {
        int checks=0;
        for(int channel=0;channel<3;channel++) {
            HashSet<Integer> codes=new HashSet<>();
            for(int view=0;view<32;view++) {
                int code=PanelTestImages.code(view,channel);
                if(code<1||code>254||!codes.add(code))throw new AssertionError("Ambiguous encoded view"); checks++;
            }
        }
        HashSet<Integer> cards=new HashSet<>();
        for(int view=0;view<20;view++) {
            byte[] card=PanelTestImages.card(view,160,256);
            if(!cards.add(Arrays.hashCode(card)))throw new AssertionError("Repeated view image"); checks++;
            for(int i=3;i<card.length;i+=4) {if((card[i]&255)!=255)throw new AssertionError("Not opaque");checks++;}
        }
        // In '01' the units digit has no top horizontal segment; in '02' it does.
        int px=115,y=256-1-79,index=(y*160+px)*4;
        if((PanelTestImages.card(0,160,256)[index]&255)==255)throw new AssertionError("01 top segment");
        if((PanelTestImages.card(1,160,256)[index]&255)!=255)throw new AssertionError("02 top segment");
        System.out.println("PanelTestImagesTest: "+(checks+2)+" checks passed");
    }
}
