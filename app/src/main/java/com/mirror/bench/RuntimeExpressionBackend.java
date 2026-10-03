package com.mirror.bench;

import android.os.Bundle;

/** Explicit debug experiment only; never writes preferences or changes the CPU default. */
final class RuntimeExpressionBackend {
    private RuntimeExpressionBackend() {}
    @SuppressWarnings("deprecation")
    static boolean read(Bundle extras,boolean debug) {
        String key="test_npu_blendshapes";
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))
            throw new IllegalArgumentException("NPU expression override requires debug Boolean");
        return (Boolean)value;
    }
}
