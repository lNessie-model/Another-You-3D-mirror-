package com.mirror.bench;

import android.os.Bundle;

/** Explicit debug input experiments only; never write preferences or select stored avatars. */
final class RuntimeExpressionBackend {
    private RuntimeExpressionBackend() {}
    @SuppressWarnings("deprecation")
    static boolean read(Bundle extras,boolean debug) {
        return readBoolean(extras,debug,"test_npu_blendshapes");
    }
    static boolean readPrivateHead(Bundle extras,boolean debug) {
        return readBoolean(extras,debug,"test_private_head");
    }
    @SuppressWarnings("deprecation")
    private static boolean readBoolean(Bundle extras,boolean debug,String key) {
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))
            throw new IllegalArgumentException(key+" override requires debug Boolean");
        return (Boolean)value;
    }
}
