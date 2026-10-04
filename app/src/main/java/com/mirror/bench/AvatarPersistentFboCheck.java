package com.mirror.bench;

import android.content.res.AssetManager;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Strict RGBA equality diagnostic: same prepared pose/VBO, legacy OVR4 vs fixed OVR4 FBO groups. */
public final class AvatarPersistentFboCheck {
    private AvatarPersistentFboCheck(){}
    public static JSONObject run(AssetManager assets,int width,int height,int views,float aspect,BooleanSupplier cancelled)throws Exception {
        return AvatarMultiviewCheck.runComparison(assets,width,height,views,aspect,cancelled,false,true);
    }
    static boolean pixelsMatch(AvatarPixelComparison.Stats stats){
        return stats.passed()&&stats.rgbMismatches==0&&stats.alphaMismatches==0;
    }
}
