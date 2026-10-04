package com.mirror.bench;

import android.content.Intent;
import android.os.Bundle;

/** Fixed configured counts plus ephemeral debug overrides; never writes settings. */
final class RuntimeViewCount {
    static final String EXTRA="test_view_count";
    static final String CONFIGURED_EXTRA="runtime_view_count";
    private RuntimeViewCount() {}
    static int read(Bundle extras,boolean debug) {
        if(extras==null||!extras.containsKey(EXTRA))return 20;
        Object raw=extras.get(EXTRA);
        if(!debug||!(raw instanceof Integer)||((Integer)raw!=16&&(Integer)raw!=20))
            throw new IllegalArgumentException("View count override requires debug Integer 16 or 20");
        return (Integer)raw;
    }
    static int read(Bundle extras,boolean debug,int savedViews) {
        if(!allowed(savedViews))throw new IllegalArgumentException("Unsupported saved view count");
        return extras==null||!extras.containsKey(EXTRA)?savedViews:read(extras,debug);
    }
    // Only calibration/preview consume this count of the already running main session.
    static int readConfigured(Bundle extras,boolean debug,int fallbackViews) {
        if(!allowed(fallbackViews))throw new IllegalArgumentException("Unsupported configured fallback view count");
        if(extras==null||!extras.containsKey(CONFIGURED_EXTRA))return read(extras,debug,fallbackViews);
        Object raw=extras.get(CONFIGURED_EXTRA);
        if(extras.containsKey(EXTRA)||!(raw instanceof Integer)||!allowed((Integer)raw))
            throw new IllegalArgumentException("Configured preview count requires Integer 16 or 20 without a debug override");
        return (Integer)raw;
    }
    static Intent forwardConfigured(Intent intent,int views,boolean debug) {
        if(!allowed(views))throw new IllegalArgumentException("Unsupported configured preview count");
        return debug?forward(intent,views,true):intent.putExtra(CONFIGURED_EXTRA,views);
    }
    static Intent forward(Intent intent,int views,boolean debug) {
        if((views!=16&&views!=20)||(!debug&&views!=20))
            throw new IllegalArgumentException("Only debug may forward view count 16 or 20");
        // Release callers do not emit an explicit override that a release receiver must reject.
        if(debug)intent.putExtra(EXTRA,views);
        return intent;
    }
    private static boolean allowed(int views) {return views==16||views==20;}
}
