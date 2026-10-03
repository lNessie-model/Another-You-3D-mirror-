package com.mirror.bench;

import android.content.res.AssetManager;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Explicit, synchronous readback diagnostic; never a performance capture. */
public final class AvatarBatchCheck {
    private AvatarBatchCheck(){}
    public static JSONObject run(AssetManager assets,int width,int height,int views,float physicalAspect,
                                 BooleanSupplier cancelled)throws Exception {
        return AvatarMultiviewCheck.runComparison(assets,width,height,views,physicalAspect,cancelled,true);
    }
}
